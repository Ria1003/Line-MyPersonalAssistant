package com.mypa.line_mypersonalassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mypa.line_mypersonalassistant.ai.AiParser;
import com.mypa.line_mypersonalassistant.ai.PendingCommandService;
import com.mypa.line_mypersonalassistant.ai.Command;
import com.mypa.line_mypersonalassistant.ai.Intent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.*;

@RestController
public class LineBotController {
    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter MDHM_FMT = DateTimeFormatter.ofPattern("M/d HH:mm");

    // Natural-language fallbacks (works even when OpenAI is disabled / down)
    private static final Pattern NL_REMIND_CN = Pattern.compile(
            "^提醒我\\s*(?:(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})|(\\d{1,2})[-/](\\d{1,2})(?:[-/](\\d{2,4}))?)\\s*(\\d{1,2}):(\\d{2})\\s*(.+)$"
    );
    private static final Pattern NL_MONEY = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern NL_EXPENSE_VERB = Pattern.compile("(花了|付了|買了|刷了|繳了|消費|支出)");
    private static final Pattern NL_INCOME_VERB = Pattern.compile("(收到|入帳|薪水|退款|退費|收入)");

    private final ExpenseService expenseService;
    private final TodoService todoService;
    private final ReminderService reminderService;
    private final PendingReminderService pendingReminderService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LineReplyService lineReplyService;
    private final AiParser aiParser;
    private final PendingCommandService pendingCommandService;

    public LineBotController(LineReplyService lineReplyService,
                             TodoService todoService,
                             ExpenseService expenseService,
                             ReminderService reminderService,
                             PendingReminderService pendingReminderService,
                             AiParser aiParser,
                             PendingCommandService pendingCommandService) {
        this.lineReplyService = lineReplyService;
        this.todoService = todoService;
        this.expenseService = expenseService;
        this.reminderService = reminderService;
        this.pendingReminderService = pendingReminderService;
        this.aiParser = aiParser;
        this.pendingCommandService = pendingCommandService;
    }

    private void executeAiCommand(Command cmd, String userId, String replyToken) {
        switch (cmd.intent) {
            case ADD_TODO -> {
                String text = cmd.slotString("text");
                todoService.addTodo(userId, text);
                lineReplyService.replyText(replyToken, "✅ 已加入待辦：" + text);
            }
            case LIST_TODO -> {
                if (todoService.isEmpty(userId)) {
                    lineReplyService.replyText(replyToken, "📭 目前沒有待辦事項");
                    return;
                }
                StringBuilder sb = new StringBuilder("📋 你的待辦事項：\n");
                int i = 1;
                for (String todo : todoService.getTodos(userId)) {
                    sb.append(i++).append(". ").append(todo).append("\n");
                }
                lineReplyService.replyText(replyToken, sb.toString());
            }

            case DONE_TODO -> {
                Integer idx = cmd.slotInt("index");
                String text = cmd.slotString("text");

                String doneText = null;

                boolean hasValidIndex = (idx != null && idx >= 1);

                if (hasValidIndex) {
                    doneText = todoService.completeTodo(userId, idx);
                }

                if (doneText == null && text != null && !text.isBlank()) {
                    doneText = todoService.completeByText(userId, text);
                }

                if (doneText == null) {
                    lineReplyService.replyText(
                            replyToken,
                            "⚠️ 找不到符合的待辦事項\n你可以用：Done 1 或直接輸入待辦內容"
                    );
                    return;
                }

                lineReplyService.replyText(replyToken, "✅ 已完成：\n" + doneText);
            }

            case ADD_EXPENSE -> {
                Double amount = cmd.slotDouble("amount");
                String item = cmd.slotString("item");
                if (amount == null || item == null || item.isBlank()) {
                    pendingCommandService.put(userId, cmd);
                    lineReplyService.replyText(replyToken, "我需要更多資訊：" + String.join(", ", cmd.missing));
                    return;
                }
                expenseService.addExpense(userId, amount, item);
                lineReplyService.replyText(replyToken, "✅ 已記錄：" + item + " " + formatAmount(amount));
            }
            case EXPENSE_LIST -> {
                var list = expenseService.list(userId);
                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "📭 目前沒有任何記帳紀錄");
                    return;
                }
                StringBuilder sb = new StringBuilder("🧾 最近紀錄（顯示 10 筆）：\n");
                for (int i = 0; i < list.size() && i < 10; i++) {
                    var r = list.get(i);
                    sb.append(i + 1).append(". ")
                            .append(formatAmount(r.amount)).append(" ")
                            .append(r.item).append("\n");
                }
                sb.append("\n刪除請輸入：$delete 編號\n例如：$delete 3");
                lineReplyService.replyText(replyToken, sb.toString());
            }
            case EXPENSE_SUM -> {
                lineReplyService.replyText(replyToken, "📌 總計：" + formatAmount(expenseService.sum(userId)));
            }
            case EXPENSE_DELETE -> {
                Integer idx = cmd.slotInt("index");
                var removed = expenseService.deleteByIndex(userId, idx);
                boolean ok = (removed != null);

                lineReplyService.replyText(replyToken, ok ? "🗑️ 已刪除第 " + idx + " 筆" : "⚠️ 找不到第 " + idx + " 筆");
            }
            case TODAY -> {
                lineReplyService.replyText(replyToken, "📅 今日紀錄：\n" + expenseService.listToday(userId)
                        + "\n\n📌 今日總計：" + formatAmount(expenseService.sumToday(userId)));
            }
            case MONTH -> {
                lineReplyService.replyText(replyToken, "🗓️ 本月紀錄：\n" + expenseService.listThisMonth(userId)
                        + "\n\n📌 本月總計：" + formatAmount(expenseService.sumThisMonth(userId)));
            }
            case REMIND_CREATE -> {
                String date = cmd.slotString("date");
                String time = cmd.slotString("time");
                String text = cmd.slotString("text");

                if (date == null || time == null || text == null) {
                    pendingCommandService.put(userId, cmd);
                    lineReplyService.replyText(replyToken, "我需要更多資訊：" + String.join(", ", cmd.missing));
                    return;
                }

                LocalDate d = LocalDate.parse(date);
                String[] hm = time.split(":");
                int hour = Integer.parseInt(hm[0]);
                int minute = Integer.parseInt(hm[1]);
                LocalDateTime eventTime = d.atTime(hour, minute);

                PendingReminderService.Pending p = new PendingReminderService.Pending();
                p.eventTime = eventTime;
                p.text = text;
                p.rawDisplay = date + " " + time + " " + text;
                pendingReminderService.put(userId, p);

                lineReplyService.replyText(replyToken,
                        "請問要提前多久提醒(回覆2代表10 min)：\n" +
                                "1. 1 min\n" +
                                "2. 3 min\n" +
                                "3. 5 min\n" +
                                "4. 10 min\n" +
                                "5. 30 min\n" +
                                "6. 1 hour\n" +
                                "7. 1 day"
                );
            }

            case REMIND_LIST -> {
                var list = reminderService.listUpcoming(userId);

                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "📭 目前沒有未來提醒");
                    return;
                }

                StringBuilder sb = new StringBuilder("⏰ 未來提醒：\n");
                int i = 1;
                for (var r : list) {
                    String eventStr = java.time.Instant.ofEpochMilli(r.eventAt)
                            .atZone(ZONE)
                            .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"));

                    String remindStr = java.time.Instant.ofEpochMilli(r.remindAt)
                            .atZone(ZONE)
                            .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"));

                    sb.append(i++).append(". ")
                            .append(eventStr).append(" ").append(r.text)
                            .append("（提醒：").append(remindStr).append("）\n");
                }

                sb.append("\n刪除：remind delete 編號\n例如：remind delete 2");
                lineReplyService.replyText(replyToken, sb.toString());
            }

            case REMIND_DELETE -> {
                Integer idx = cmd.slotInt("index");
                if (idx == null) {
                    pendingCommandService.put(userId, cmd);
                    lineReplyService.replyText(replyToken, "我需要更多資訊：index");
                    return;
                }

                var removed = reminderService.deleteUpcomingByIndex(userId, idx);
                if (removed == null) {
                    lineReplyService.replyText(replyToken, "⚠️ 找不到提醒 " + idx);
                    return;
                }

                String eventStr = java.time.Instant.ofEpochMilli(removed.eventAt)
                        .atZone(ZONE)
                        .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"));

                lineReplyService.replyText(replyToken, "🗑️ 已刪除提醒：\n" + eventStr + " " + removed.text);
            }

            case HELP -> lineReplyService.replyText(replyToken, helpText());
            default -> lineReplyService.replyText(replyToken, "我看不太懂，你可以輸入 functions 看可用指令🙂");
        }
    }

    private Command fillPendingSlots(Command cmd, String userText) {
        if (cmd == null) return null;
        if (cmd.slots == null) cmd.slots = new java.util.HashMap<>();

        String t = userText == null ? "" : userText.trim();
        if (t.isEmpty()) return cmd;

        String normalizedDate = normalizeDateShortcut(t);

        for (String need : new java.util.ArrayList<>(cmd.missing)) {
            switch (need) {
                case "date" -> {
                    if (looksLikeDate(normalizedDate)) cmd.slots.put("date", normalizedDate);
                }
                case "time" -> {
                    String time = normalizeTime(t);
                    if (time != null) cmd.slots.put("time", time);
                }
                case "index" -> {
                    Integer idx = parseIntSafe(t);
                    if (idx != null) cmd.slots.put("index", idx);
                }
                case "amount" -> {
                    Double a = parseAmountSafe(t);
                    if (a != null) cmd.slots.put("amount", a);
                }
                case "item" -> cmd.slots.put("item", t);
                case "text" -> cmd.slots.put("text", t);
                default -> {
                }
            }
        }
        return cmd;
    }

    private String normalizeDateShortcut(String text) {
        Matcher m = Pattern
                .compile("^(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?$")
                .matcher(text.trim());
        if (!m.matches()) return text;

        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        String y = m.group(3);

        int year;
        if (y == null) {
            year = java.time.LocalDate.now().getYear();
        } else {
            int yy = Integer.parseInt(y);
            year = (y.length() == 2) ? (2000 + yy) : yy;
        }

        return String.format("%04d-%02d-%02d", year, month, day);
    }

    private boolean looksLikeDate(String s) {
        return s != null && s.matches("^\\d{4}-\\d{2}-\\d{2}$");
    }

    private String normalizeTime(String t) {
        String s = t.trim();
        Matcher m = Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(s);
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h < 0 || h > 23 || min < 0 || min > 59) return null;
        return String.format("%02d:%02d", h, min);
    }

    private Integer parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Double parseAmountSafe(String s) {
        String cleaned = s.trim().replaceAll("[,$\\s]", "");
        Matcher m = Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$").matcher(cleaned);
        if (!m.matches()) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatAmount(double a) {
        java.text.DecimalFormat df = new java.text.DecimalFormat("0.##");
        String num = df.format(Math.abs(a));
        return (a >= 0 ? "+" : "-") + num;
    }

    @PostMapping("/callback")
    public String callback(
            @RequestHeader("X-Line-Signature") String signature,
            @RequestBody String body
    ) throws Exception {

        System.out.println("=== 收到 LINE webhook ===");
        System.out.println("Signature: " + signature);
        System.out.println("Body: " + body);

        JsonNode root = objectMapper.readTree(body);
        JsonNode events = root.path("events");
        if (!events.isArray()) return "OK";

        for (JsonNode event : events) {
            String eventType = event.path("type").asText();
            if (!"message".equals(eventType)) continue;

            String userId = event.path("source").path("userId").asText();
            if (userId == null || userId.isBlank()) userId = "UNKNOWN";

            String replyToken = event.path("replyToken").asText();
            if (replyToken == null || replyToken.isBlank()) continue;

            JsonNode messageNode = event.path("message");
            String messageType = messageNode.path("type").asText();

            if (!"text".equals(messageType)) {
                lineReplyService.replyText(replyToken, "我目前只看得懂文字訊息🙂");
                continue;
            }

            String userText = messageNode.path("text").asText().trim();

            // 0) If we are in the middle of an AI follow-up, try to fill missing slots first
            Command pendingCmd = pendingCommandService.get(userId);
            if (pendingCmd != null) {
                Command filled = fillPendingSlots(pendingCmd, userText);
                filled.missing.clear();
                switch (filled.intent) {
                    case ADD_TODO -> filled.require("text");
                    case DONE_TODO, EXPENSE_DELETE, REMIND_DELETE -> filled.require("index");
                    case ADD_EXPENSE -> filled.require("amount", "item");
                    case REMIND_CREATE -> filled.require("date", "time", "text");
                    default -> {
                    }
                }

                if (!filled.missing.isEmpty()) {
                    pendingCommandService.put(userId, filled);
                    lineReplyService.replyText(replyToken, "我需要更多資訊：" + String.join(", ", filled.missing));
                    continue;
                } else {
                    pendingCommandService.clear(userId);
                    executeAiCommand(filled, userId, replyToken);
                    continue;
                }
            }

            System.out.println("使用者說: " + userText);

            // 你原本的各種指令（$list, $delete, $, Sum, today, month, +, remind..., etc）
            // 這一段我保留你原始專案內容不動
            // ---- 下面開始仍然是你原本的 if/return 區塊 ----

            if (userText.equalsIgnoreCase("$list")) {
                var list = expenseService.list(userId);

                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "📭 目前沒有任何記帳紀錄");
                    return "OK";
                }

                StringBuilder sb = new StringBuilder("🧾 最近紀錄（顯示 10 筆）：\n");
                int shown = 0;

                for (int i = 0; i < list.size(); i++) {
                    var r = list.get(i);
                    sb.append(i + 1).append(". ")
                            .append(formatAmount(r.amount))
                            .append(" ").append(r.item)
                            .append("\n");
                    shown++;
                    if (shown >= 10) break;
                }

                sb.append("\n刪除請輸入：$delete 編號\n例如：$delete 3");

                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            if (userText.matches("^\\$delete\\s+\\d+$")) {

                int index = Integer.parseInt(userText.replaceAll("\\D+", ""));
                var removed = expenseService.deleteByIndex(userId, index);

                if (removed == null) {
                    lineReplyService.replyText(replyToken, "❌ 找不到第 " + index + " 筆紀錄（先用 $list 看編號）");
                    return "OK";
                }

                String type = removed.amount >= 0 ? "進帳" : "支出";
                lineReplyService.replyText(
                        replyToken,
                        "🗑️ 已刪除第 " + index + " 筆（" + type + "）\n" +
                                (removed.amount >= 0 ? "+" : "") + removed.amount + " " + removed.item);
                return "OK";
            }

            if (userText.startsWith("$")) {

                String content = userText.substring(1).trim();
                String[] parts = content.split("\\s+", 2);
                if (parts.length < 2) {
                    lineReplyService.replyText(replyToken,
                            "❌ 記帳格式錯誤\n請使用：\n$-50 午餐\n$+90000 薪水\n$28.5 買菜（未加符號預設為支出）");
                    return "OK";
                }

                String amountStr = parts[0].trim().replace(",", "");
                String item = parts[1].trim();

                Double parsed = parseAmountLoose(amountStr);
                if (parsed == null) {
                    lineReplyService.replyText(replyToken,
                            "❌ 金額格式錯誤\n例如：$-50 午餐、$+90000 薪水、$28.5 買菜");
                    return "OK";
                }

                double amount = parsed;
                if (!(amountStr.startsWith("+") || amountStr.startsWith("-"))) {
                    amount = -Math.abs(amount);
                }

                expenseService.addExpense(userId, amount, item);
                String type = amount >= 0 ? "進帳" : "支出";

                lineReplyService.replyText(replyToken,
                        "✅ 已記錄" + type + "\n" + formatAmount(amount) + " 元\n" + "備註：" + item);

                return "OK";
            }

            if (userText.equalsIgnoreCase("Sum")) {
                double total = expenseService.sum(userId);
                var list = expenseService.list(userId);

                StringBuilder sb = new StringBuilder();
                sb.append("📊 目前累計：").append(formatAmount(total)).append("元\n");

                sb.append("🕒 最近 5 筆：\n");
                int start = Math.max(0, list.size() - 5);
                for (int i = start; i < list.size(); i++) {
                    ExpenseService.Record r = list.get(i);
                    sb.append("- ").append(formatAmount(r.amount)).append(" ").append(r.item).append("\n");
                }

                lineReplyService.replyText(replyToken, sb.toString());
                continue;
            }

            if (userText.equalsIgnoreCase("today")) {

                var list = expenseService.listToday(userId);
                double total = expenseService.sumToday(userId);

                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "📅 今天沒有任何紀錄\n今日總計：0");
                    return "OK";
                }

                StringBuilder sb = new StringBuilder("📅 今天紀錄：\n");
                int i = 1;
                for (var r : list) {
                    sb.append(i++).append(". ")
                            .append(formatAmount(r.amount))
                            .append(" ").append(r.item).append("\n");
                    if (i > 10) break;
                }
                sb.append("\n今日總計：").append(formatAmount(total));

                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            if (userText.equalsIgnoreCase("month")) {
                var list = expenseService.listThisMonth(userId);
                double total = expenseService.sumThisMonth(userId);

                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "📅 本月沒有任何紀錄\n本月總計：0");
                    return "OK";
                }

                StringBuilder sb = new StringBuilder("📅 本月紀錄：\n");
                int i = 1;
                for (var r : list) {
                    sb.append(i++).append(". ")
                            .append(r.amount >= 0 ? "+" : "").append(r.amount)
                            .append(" ").append(r.item).append("\n");
                    if (i > 10) break;
                }
                sb.append("\n本月總計：").append(total);

                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            if (userText.startsWith("+")) {
                String todoText = userText.substring(1).trim();

                if (todoText.isEmpty()) {
                    lineReplyService.replyText(replyToken, "⚠️ 請輸入待辦內容，例如：\n+ 買牛奶");
                    return "OK";
                }

                todoService.addTodo(userId, todoText);
                lineReplyService.replyText(replyToken, "✅ 已新增待辦事項：\n" + todoText);
                return "OK";
            }

            if (userText.matches("(?i)^remind\\s+\\d{1,2}/\\d{1,2}\\s+\\d{1,2}:\\d{2}\\s+.+$")) {
                String[] parts = userText.split("\\s+", 4);
                String md = parts[1];
                String hm = parts[2];
                String text = parts[3].trim();

                String[] mdParts = md.split("/");
                int month = Integer.parseInt(mdParts[0]);
                int day = Integer.parseInt(mdParts[1]);

                String[] hmParts = hm.split(":");
                int hour = Integer.parseInt(hmParts[0]);
                int minute = Integer.parseInt(hmParts[1]);

                LocalDate today = LocalDate.now(ZONE);
                int year = today.getYear();
                LocalDate date = LocalDate.of(year, month, day);
                if (date.isBefore(today)) {
                    date = LocalDate.of(year + 1, month, day);
                }

                LocalDateTime eventTime = date.atTime(hour, minute);

                PendingReminderService.Pending p = new PendingReminderService.Pending();
                p.eventTime = eventTime;
                p.text = text;
                p.rawDisplay = md + " " + hm + " " + text;
                pendingReminderService.put(userId, p);

                lineReplyService.replyText(replyToken,
                        "請問要提前多久提醒(回覆2代表10 min)：\n" +
                                "1. 1 min\n" +
                                "2. 3 min\n" +
                                "3. 5 min\n" +
                                "4. 10 min\n" +
                                "5. 30 min\n" +
                                "6. 1 hour\n" +
                                "7. 1 day"
                );
                return "OK";
            }

            if (pendingReminderService.has(userId)) {
                if (!userText.matches("^[1-7]$")) {
                    lineReplyService.replyText(replyToken, "請輸入編號 選擇提前多久提醒🙂");
                    return "OK";
                }

                var p = pendingReminderService.remove(userId);
                int choice = Integer.parseInt(userText);

                java.time.Duration advance = switch (choice) {
                    case 1 -> java.time.Duration.ofMinutes(1);
                    case 2 -> java.time.Duration.ofMinutes(3);
                    case 3 -> java.time.Duration.ofMinutes(5);
                    case 4 -> java.time.Duration.ofMinutes(10);
                    case 5 -> java.time.Duration.ofMinutes(30);
                    case 6 -> java.time.Duration.ofHours(1);
                    case 7 -> java.time.Duration.ofDays(1);
                    default -> java.time.Duration.ofMinutes(10);
                };

                reminderService.add(userId, p.eventTime, advance, p.text);

                var remindTime = p.eventTime.minus(advance);
                String msg = "✅ 已設定提醒\n" +
                        "事件：" + p.rawDisplay + "\n" +
                        "提醒時間：" + remindTime.getMonthValue() + "/" + remindTime.getDayOfMonth() +
                        " " + String.format("%02d:%02d", remindTime.getHour(), remindTime.getMinute());

                lineReplyService.replyText(replyToken, msg);
                return "OK";
            }

            if (userText.equalsIgnoreCase("remind list")) {
                var list = reminderService.listUpcoming(userId);

                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "📭 目前沒有未來提醒");
                    return "OK";
                }

                StringBuilder sb = new StringBuilder("⏰ 未來提醒：\n");
                int i = 1;
                for (var r : list) {
                    String eventStr = java.time.Instant.ofEpochMilli(r.eventAt)
                            .atZone(ZoneId.of("America/Los_Angeles"))
                            .format(DateTimeFormatter.ofPattern("M/d HH:mm"));

                    String remindStr = java.time.Instant.ofEpochMilli(r.remindAt)
                            .atZone(ZoneId.of("America/Los_Angeles"))
                            .format(DateTimeFormatter.ofPattern("M/d HH:mm"));

                    sb.append(i++).append(". ")
                            .append(eventStr).append(" ").append(r.text)
                            .append("（提醒：").append(remindStr).append("）\n");
                }

                sb.append("\n刪除：remind delete 編號\n例如：remind delete 2");

                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            if (userText.matches("(?i)^remind\\s+delete\\s+\\d+$")) {
                int idx = Integer.parseInt(userText.replaceAll("\\D+", ""));
                var removed = reminderService.deleteUpcomingByIndex(userId, idx);

                if (removed == null) {
                    lineReplyService.replyText(replyToken, "❌ 找不到第 " + idx + " 筆（先輸入 Remind list 看編號）");
                    return "OK";
                }

                String eventStr = java.time.Instant.ofEpochMilli(removed.eventAt)
                        .atZone(ZoneId.of("America/Los_Angeles"))
                        .format(DateTimeFormatter.ofPattern("M/d HH:mm"));

                lineReplyService.replyText(replyToken,
                        "🗑️ 已刪除提醒：\n" + eventStr + " " + removed.text);
                return "OK";
            }

            if (userText.equalsIgnoreCase("List")) {
                if (todoService.isEmpty(userId)) {
                    lineReplyService.replyText(replyToken, "📭 目前沒有待辦事項");
                    return "OK";
                }

                StringBuilder sb = new StringBuilder("📋 你的待辦事項：\n");
                int i = 1;
                for (String todo : todoService.getTodos(userId)) {
                    sb.append(i++).append(". ").append(todo).append("\n");
                }

                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            if (userText.startsWith("Done")) {
                try {
                    int index = Integer.parseInt(userText.substring(4).trim());
                    String completed = todoService.completeTodo(userId, index);

                    if (completed == null) {
                        lineReplyService.replyText(replyToken, "⚠️ 找不到這個編號的待辦事項");
                    } else {
                        lineReplyService.replyText(replyToken, "✅ 已完成：\n" + completed);
                    }
                } catch (NumberFormatException e) {
                    lineReplyService.replyText(replyToken, "⚠️ 請輸入完成事項編號(Ex. Done 1)\n不清楚編號時請輸入list");
                }
                return "OK";
            }

            if (userText.equalsIgnoreCase("functions")) {
                lineReplyService.replyText(replyToken,
                        "📌 可用指令：\n" +
                                "• + 待辦事項\n" +
                                "• list\n" +
                                "• done 事項編號\n" +
                                "• $金額 備註\n" +
                                "• sum\n" +
                                "• $list\n" +
                                "• today\n" +
                                "• month\n" +
                                "• remind 日期 時間 事項\n" +
                                "• remind list\n" +
                                "• functions"
                );
                continue;
            }

            // --- Natural-language fallback (before AI) ---
            // Examples:
            // 1) 我剛剛買菜花了50
            // 2) 提醒我 1/19 15:20喝水
            if (tryParseNaturalReminder(userText, userId, replyToken)) {
                return "OK";
            }
            if (tryParseNaturalExpense(userText, userId, replyToken)) {
                return "OK";
            }

            // --- AI parser (Structured Output) ---
            if (!aiParser.isEnabled()) {
                String reason = aiParser.getLastError();
                if (reason == null || reason.isBlank()) {
                    reason = "OpenAI 未啟用（可能缺少 OPENAI_API_KEY）";
                }
                lineReplyService.replyText(replyToken,
                        "⚠️ 目前無法使用自然語言解析（AI Parser 未啟用/不可用）\n" +
                                reason +
                                "\n\n你可以改用 functions 內的指令格式。"
                );
                return "OK";
            }

            Command cmd = aiParser.parse(userText);
            if (cmd.intent != Intent.UNKNOWN) {
                if (cmd.missing != null && !cmd.missing.isEmpty()) {
                    pendingCommandService.put(userId, cmd);
                    lineReplyService.replyText(replyToken, "我需要更多資訊：" + String.join(", ", cmd.missing));
                    return "OK";
                }
                executeAiCommand(cmd, userId, replyToken);
                return "OK";
            }

            lineReplyService.replyText(replyToken,
                    "我看不懂這個指令 🤔\n\n可用指令：\n" +
                            "+ 待辦事項\n" +
                            "List\n" +
                            "Done 事項編號\n" +
                            "$金額 備註（預設支出，可用 +100 表示收入）\n" +
                            "sum\n" +
                            "$list\n" +
                            "today\n" +
                            "month\n" +
                            "Remind 日期 時間 事項\n" +
                            "Remind list\n" +
                            "functions");
        }

        return "OK";
    }

    private String helpText() {
        return "📌 可用指令：\n" +
                "• + 待辦事項\n" +
                "• list\n" +
                "• done 事項編號\n" +
                "• $金額 備註\n" +
                "• sum\n" +
                "• $list\n" +
                "• today\n" +
                "• month\n" +
                "• remind 日期 時間 事項\n" +
                "• remind list\n" +
                "• functions";
    }

    /**
     * Natural language reminder parser.
     * Supports: 提醒我 1/19 15:20喝水 (spaces optional)
     * Supports: 提醒我 2026-01-19 15:20 喝水
     */
    private boolean tryParseNaturalReminder(String userText, String userId, String replyToken) {
        if (userText == null) return false;
        String t = userText.trim();
        if (t.isEmpty()) return false;

        Matcher m = NL_REMIND_CN.matcher(t);
        if (!m.matches()) return false;

        int year;
        int month;
        int day;
        if (m.group(1) != null) {
            year = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(2));
            day = Integer.parseInt(m.group(3));
        } else {
            month = Integer.parseInt(m.group(4));
            day = Integer.parseInt(m.group(5));
            String y = m.group(6);

            LocalDate today = LocalDate.now(ZONE);
            if (y == null || y.isBlank()) {
                year = today.getYear();
                LocalDate candidate = LocalDate.of(year, month, day);
                if (candidate.isBefore(today)) year = year + 1;
            } else {
                int yy = Integer.parseInt(y);
                year = (y.length() == 2) ? (2000 + yy) : yy;
            }
        }

        int hour = Integer.parseInt(m.group(7));
        int minute = Integer.parseInt(m.group(8));
        String text = m.group(9) == null ? "" : m.group(9).trim();
        if (text.isBlank()) return false;

        LocalDateTime eventTime;
        try {
            eventTime = LocalDate.of(year, month, day).atTime(hour, minute);
        } catch (Exception e) {
            lineReplyService.replyText(replyToken, "⚠️ 日期時間格式錯誤，請用：提醒我 1/19 15:20 喝水");
            return true;
        }

        PendingReminderService.Pending p = new PendingReminderService.Pending();
        p.eventTime = eventTime;
        p.text = text;
        p.rawDisplay = eventTime.format(MDHM_FMT) + " " + text;
        pendingReminderService.put(userId, p);

        lineReplyService.replyText(replyToken,
                "請問要提前多久提醒(回覆2代表10 min)：\n" +
                        "1. 1 min\n" +
                        "2. 3 min\n" +
                        "3. 5 min\n" +
                        "4. 10 min\n" +
                        "5. 30 min\n" +
                        "6. 1 hour\n" +
                        "7. 1 day"
        );
        return true;
    }

    /**
     * Natural language expense/income parser.
     * Examples:
     *  - 我剛剛買菜花了50
     *  - 我買咖啡 6.5
     *  - 剛剛刷了120 午餐
     *  - 收到退款100
     *
     * Rule:
     *  - If contains income verbs => amount positive
     *  - Else if contains expense verbs or "花/付/買/刷/繳/消費/支出" => amount negative
     *  - If no verb, we do NOT parse (avoid false positives)
     */
    private boolean tryParseNaturalExpense(String userText, String userId, String replyToken) {
        if (userText == null) return false;
        String t = userText.trim();
        if (t.isEmpty()) return false;

        boolean isIncome = NL_INCOME_VERB.matcher(t).find();
        boolean isExpense = NL_EXPENSE_VERB.matcher(t).find();

        // Without a clear verb, skip parsing to avoid accidental capture.
        if (!isIncome && !isExpense) return false;

        Matcher mm = NL_MONEY.matcher(t.replace(",", ""));
        if (!mm.find()) return false;

        Double value;
        try {
            value = Double.parseDouble(mm.group(1));
        } catch (Exception e) {
            return false;
        }

        // Determine sign
        double amount;
        if (isIncome) {
            amount = Math.abs(value);
        } else {
            // expense
            amount = -Math.abs(value);
        }

        // Extract an "item" roughly: remove amount and common verbs/particles, keep remaining text
        String item = t;

        // Remove leading phrases like "我剛剛" / "剛剛" / "剛才"
        item = item.replaceFirst("^(我\\s*)?(剛剛|剛才)\\s*", "");

        // Remove verbs
        item = item.replaceAll("(花了|付了|買了|買|刷了|刷|繳了|繳|消費|支出|收到|入帳|薪水|退款|退費|收入)", " ");

        // Remove the first matched amount string
        item = item.replaceFirst(Pattern.quote(mm.group(1)), " ");

        // Cleanup punctuation/spaces
        item = item.replaceAll("[,，。．!！?？:：;；()（）\\[\\]{}\"'`]", " ");
        item = item.replaceAll("\\s+", " ").trim();

        // If still empty, pick a default label
        if (item.isBlank()) item = isIncome ? "收入" : "支出";

        expenseService.addExpense(userId, amount, item);
        lineReplyService.replyText(replyToken, "✅ 已記錄：" + item + " " + formatAmount(amount));
        return true;
    }

    /**
     * Loose amount parser for the $ command flow.
     * Accepts:
     *  - +100
     *  - -50
     *  - 28.5
     *  - 1,234.56
     */
    private Double parseAmountLoose(String amountStr) {
        if (amountStr == null) return null;
        String s = amountStr.trim().replaceAll("[,$\\s]", "");
        if (s.isEmpty()) return null;

        // allow leading +/-
        if (!s.matches("^[+-]?\\d+(?:\\.\\d+)?$")) return null;

        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }
}


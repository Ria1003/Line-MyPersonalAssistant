package com.mypa.line_mypersonalassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
<<<<<<< HEAD

=======
import com.mypa.line_mypersonalassistant.ai.AiParser;
import com.mypa.line_mypersonalassistant.ai.PendingCommandService;
import com.mypa.line_mypersonalassistant.ai.Command;
import com.mypa.line_mypersonalassistant.ai.Intent;
>>>>>>> 96b0e50 (Fix AI missing-field flow and date normalization)
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;

import org.springframework.web.bind.annotation.*;

@RestController
public class LineBotController {
    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private final ExpenseService expenseService;
    private final TodoService todoService;
    private final ReminderService reminderService;
    private final PendingReminderService pendingReminderService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LineReplyService lineReplyService;
<<<<<<< HEAD
=======
    private final AiParser aiParser;
    private final PendingCommandService pendingCommandService;
>>>>>>> 96b0e50 (Fix AI missing-field flow and date normalization)

    public LineBotController(LineReplyService lineReplyService, 
                             TodoService todoService, 
                             ExpenseService expenseService, 
                             ReminderService reminderService, 
<<<<<<< HEAD
                             PendingReminderService pendingReminderService) {
=======
                             PendingReminderService pendingReminderService,
                             AiParser aiParser,
                             PendingCommandService pendingCommandService) {
>>>>>>> 96b0e50 (Fix AI missing-field flow and date normalization)
        this.lineReplyService = lineReplyService;
        this.todoService = todoService;
        this.expenseService = expenseService;
        this.reminderService = reminderService;
        this.pendingReminderService = pendingReminderService;
<<<<<<< HEAD
=======
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
                String list = todoService.listTodos(userId);
                lineReplyService.replyText(replyToken, list);
            }
            case DONE_TODO -> {
                Integer idx = cmd.slotInt("index");
                boolean ok = todoService.markDone(userId, idx);
                lineReplyService.replyText(replyToken, ok ? "✅ 已完成第 " + idx + " 項" : "⚠️ 找不到第 " + idx + " 項");
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
                lineReplyService.replyText(replyToken, expenseService.listText(userId));
            }
            case EXPENSE_SUM -> {
                lineReplyService.replyText(replyToken, "📌 總計：" + formatAmount(expenseService.sum(userId)));
            }
            case EXPENSE_DELETE -> {
                Integer idx = cmd.slotInt("index");
                boolean ok = expenseService.delete(userId, idx);
                lineReplyService.replyText(replyToken, ok ? "🗑️ 已刪除第 " + idx + " 筆" : "⚠️ 找不到第 " + idx + " 筆");
            }
            case TODAY -> {
                lineReplyService.replyText(replyToken, "📅 今日紀錄：\n" + expenseService.listTodayText(userId)
                        + "\n\n📌 今日總計：" + formatAmount(expenseService.sumToday(userId)));
            }
            case MONTH -> {
                lineReplyService.replyText(replyToken, "🗓️ 本月紀錄：\n" + expenseService.listMonthText(userId)
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
                String result = pendingReminderService.createReminderFromParts(userId, date, time, text);
                lineReplyService.replyText(replyToken, result);
            }
            case REMIND_LIST -> lineReplyService.replyText(replyToken, pendingReminderService.listReminders(userId));
            case REMIND_DELETE -> {
                Integer idx = cmd.slotInt("index");
                boolean ok = pendingReminderService.deleteReminder(userId, idx);
                lineReplyService.replyText(replyToken, ok ? "🗑️ 已刪除提醒 " + idx : "⚠️ 找不到提醒 " + idx);
            }
            case HELP -> lineReplyService.replyText(replyToken, helpText());
            default -> lineReplyService.replyText(replyToken, "我看不太懂，你可以輸入 functions 看可用指令🙂");
        }
    }

    private Command fillPendingSlots(Command base, String userText) {
        // copy command so we don't mutate shared instances unexpectedly
        Command cmd = base.copy();
        if (cmd.slots == null) cmd.slots = new java.util.HashMap<>();
        String t = userText == null ? "" : userText.trim();
        if (t.isEmpty()) return cmd;

        // If the user answered with a date shortcut like 1/18, normalize it
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
                default -> {}
            }
        }
        return cmd;
    }

    private String normalizeDateShortcut(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
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
        // accept HH:mm or H:mm
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(s);
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
        // allow leading + or -
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$").matcher(cleaned);
        if (!m.matches()) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatAmount(double a) {
        // Show +/- sign and trim trailing .0
        java.text.DecimalFormat df = new java.text.DecimalFormat("0.##");
        String num = df.format(Math.abs(a));
        return (a >= 0 ? "+" : "-") + num;
>>>>>>> 96b0e50 (Fix AI missing-field flow and date normalization)
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
                // recompute missing based on intent requirements
                filled.missing.clear();
                switch (filled.intent) {
                    case ADD_TODO -> filled.require("text");
                    case DONE_TODO, EXPENSE_DELETE, REMIND_DELETE -> filled.require("index");
                    case ADD_EXPENSE -> filled.require("amount", "item");
                    case REMIND_CREATE -> filled.require("date", "time", "text");
                    default -> {}
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
            
                int index = Integer.parseInt(userText.replaceAll("\\D+", "")); // 取出數字
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
            

            // 記帳：$-50 午餐 or $+90000 薪水 or $28.5 買菜
            if(userText.startsWith("$")) {

                String content = userText.substring(1).trim(); // "-50 午餐" / "+90000 薪水" / "28.5 買菜"
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

                // If user didn't specify sign, default to expense (negative)
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

            // 結算：Sum
            if(userText.equalsIgnoreCase("Sum")) {
                double total = expenseService.sum(userId);
                var list = expenseService.list(userId);

                StringBuilder sb = new StringBuilder();
                sb.append("📊 目前累計：").append(formatAmount(total)).append("元\n");

                sb.append("🕒 最近 5 筆：\n");
                int start = Math.max(0, list.size() - 5);
                for(int i = start; i< list.size() ; i++) {
                    ExpenseService.Record r = list.get(i);
                    sb.append("- ").append(formatAmount(r.amount)).append(" ").append(r.item).append("\n");
                }

                lineReplyService.replyText(replyToken, sb.toString());
                continue;
            }
            
            // 今日清單 + 今日總計
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
                    if (i > 10) break; // 先顯示前 10 筆
                }
                sb.append("\n今日總計：").append(formatAmount(total));
            
                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            // 本月清單 + 本月總計
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

            // 新增待辦：+ 內容
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

            // remind 1/31 19:00 開會
            if (userText.matches("(?i)^remind\\s+\\d{1,2}/\\d{1,2}\\s+\\d{1,2}:\\d{2}\\s+.+$")) {

                // 切成三段：remind / 1/31 / 19:00 / 內容
                String[] parts = userText.split("\\s+", 4);
                String md = parts[1];      // 1/31
                String hm = parts[2];      // 19:00
                String text = parts[3].trim();

                String[] mdParts = md.split("/");
                int month = Integer.parseInt(mdParts[0]);
                int day = Integer.parseInt(mdParts[1]);

                String[] hmParts = hm.split(":");
                int hour = Integer.parseInt(hmParts[0]);
                int minute = Integer.parseInt(hmParts[1]);

                // 沒寫年份：用今年 or 明年
                LocalDate today = LocalDate.now(ZONE);
                int year = today.getYear();
                LocalDate date = LocalDate.of(year, month, day);
                if (date.isBefore(today)) {
                    date = LocalDate.of(year + 1, month, day);
                }

                LocalDateTime eventTime = date.atTime(hour, minute);

                // 存 pending（等待使用者選 1~5）
                PendingReminderService.Pending p = new PendingReminderService.Pending();
                p.eventTime = eventTime;
                p.text = text;
                p.rawDisplay = md + " " + hm + " " + text;
                pendingReminderService.put(userId, p);

                // 問選項
                lineReplyService.replyText(replyToken,
                        "請問要提前多久提醒(回覆2代表10 min)：\n" +
                        "1. 1 min\n" +
                        "2. 10 min\n" +
                        "3. 30 min\n" +
                        "4. 1 hour\n" +
                        "5. 1 day"
                );
                return "OK";
            }

            // 如果這個 user 正在等待選提前多久，且輸入是 1~5
            if (pendingReminderService.has(userId)) {
                // 期待 1~5
                if (!userText.matches("^[1-5]$")) {
                    lineReplyService.replyText(replyToken, "請輸入編號1~5 選擇提前多久提醒🙂");
                    return "OK";
                }
            
                // ✅ 走正常 1~5 流程：這裡才 remove
                var p = pendingReminderService.remove(userId);
                int choice = Integer.parseInt(userText);
            
                java.time.Duration advance = switch (choice) {
                    case 1 -> java.time.Duration.ofMinutes(1);
                    case 2 -> java.time.Duration.ofMinutes(10);
                    case 3 -> java.time.Duration.ofMinutes(30);
                    case 4 -> java.time.Duration.ofHours(1);
                    case 5 -> java.time.Duration.ofDays(1);
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
            

            // 列出 remind list
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
                            .atZone(java.time.ZoneId.of("America/Los_Angeles"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"));
            
                    String remindStr = java.time.Instant.ofEpochMilli(r.remindAt)
                            .atZone(java.time.ZoneId.of("America/Los_Angeles"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"));
            
                    sb.append(i++).append(". ")
                      .append(eventStr).append(" ").append(r.text)
                      .append("（提醒：").append(remindStr).append("）\n");
                }
            
                sb.append("\n刪除：remind delete 編號\n例如：remind delete 2");
            
                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            // 刪除 reminder
            if (userText.matches("(?i)^remind\\s+delete\\s+\\d+$")) {
                int idx = Integer.parseInt(userText.replaceAll("\\D+", ""));
                var removed = reminderService.deleteUpcomingByIndex(userId, idx);
            
                if (removed == null) {
                    lineReplyService.replyText(replyToken, "❌ 找不到第 " + idx + " 筆（先輸入 Remind list 看編號）");
                    return "OK";
                }
            
                String eventStr = java.time.Instant.ofEpochMilli(removed.eventAt)
                        .atZone(java.time.ZoneId.of("America/Los_Angeles"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"));
            
                lineReplyService.replyText(replyToken,
                        "🗑️ 已刪除提醒：\n" + eventStr + " " + removed.text);
                return "OK";
            }

            // 列出待辦
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

            // 完成待辦：done 1
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
            
<<<<<<< HEAD

            // 錯誤訊息
            lineReplyService.replyText(replyToken, "我看不懂這個指令 🤔\n\n可用指令：\n+ 待辦事項\nList\nDone 事項編號\n$金額 備註\nsum\n$list\ntoday\nmonth\nRemind 日期 時間 事項\nRemind list\nfunctions");
=======
            // --- AI parser (Structured Output) ---
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

            // fallback
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
>>>>>>> 96b0e50 (Fix AI missing-field flow and date normalization)

        }

        return "OK";
    }

    // -------------------- AI helpers --------------------

    private void executeAiCommand(Command cmd, String userId, String replyToken) {
        switch (cmd.intent) {
            case ADD_TODO -> {
                String text = cmd.slotString("text");
                if (text == null || text.isBlank()) {
                    lineReplyService.replyText(replyToken, "我需要更多資訊：text");
                    return;
                }
                todoService.addTodo(userId, text);
                lineReplyService.replyText(replyToken, "✅ 已新增待辦：" + text);
            }
            case LIST_TODO -> lineReplyService.replyText(replyToken, todoService.listTodos(userId));
            case DONE_TODO -> {
                Integer idx = cmd.slotInt("index");
                if (idx == null) {
                    lineReplyService.replyText(replyToken, "我需要更多資訊：index");
                    return;
                }
                boolean ok = todoService.doneTodo(userId, idx);
                lineReplyService.replyText(replyToken, ok ? "✅ 已完成第 " + idx + " 項" : "❌ 找不到第 " + idx + " 項");
            }
            case ADD_EXPENSE -> {
                Double amount = cmd.slotDouble("amount");
                if (amount == null) {
                    lineReplyService.replyText(replyToken, "我需要更多資訊：amount");
                    return;
                }
                String item = cmd.slotString("item");
                if (item == null || item.isBlank()) {
                    lineReplyService.replyText(replyToken, "我需要更多資訊：item");
                    return;
                }
                expenseService.addExpense(userId, amount, item);
                lineReplyService.replyText(replyToken, "✅ 已記錄：" + formatAmount(amount) + " " + item);
            }
            case EXPENSE_LIST -> {
                var list = expenseService.list(userId);
                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "目前沒有任何紀錄");
                    return;
                }
                StringBuilder sb = new StringBuilder("💰 最近支出/收入（最新 10 筆）\n");
                int shown = 0;
                for (int i = list.size() - 1; i >= 0 && shown < 10; i--, shown++) {
                    var r = list.get(i);
                    sb.append("#").append(i + 1).append(" ")
                            .append(formatAmount(r.amount)).append(" ")
                            .append(r.item).append("\n");
                }
                lineReplyService.replyText(replyToken, sb.toString());
            }
            case EXPENSE_SUM -> lineReplyService.replyText(replyToken, "💰 總計：" + formatAmount(expenseService.sum(userId)));
            case EXPENSE_DELETE -> {
                Integer idx = cmd.slotInt("index");
                if (idx == null) {
                    lineReplyService.replyText(replyToken, "我需要更多資訊：index");
                    return;
                }
                boolean ok = expenseService.delete(userId, idx - 1);
                lineReplyService.replyText(replyToken, ok ? "✅ 已刪除第 " + idx + " 筆" : "❌ 找不到第 " + idx + " 筆");
            }
            case TODAY -> lineReplyService.replyText(replyToken, "📅 今日合計：" + formatAmount(expenseService.sumToday(userId)));
            case MONTH -> lineReplyService.replyText(replyToken, "📅 本月合計：" + formatAmount(expenseService.sumThisMonth(userId)));
            case REMIND_CREATE -> {
                String date = cmd.slotString("date");
                String time = cmd.slotString("time");
                String text = cmd.slotString("text");
                String result = pendingReminderService.createReminderFromParts(userId, date, time, text);
                lineReplyService.replyText(replyToken, result);
            }
            case REMIND_LIST -> lineReplyService.replyText(replyToken, pendingReminderService.listReminders(userId));
            case REMIND_DELETE -> {
                Integer idx = cmd.slotInt("index");
                if (idx == null) {
                    lineReplyService.replyText(replyToken, "我需要更多資訊：index");
                    return;
                }
                boolean ok = pendingReminderService.deleteReminder(userId, idx);
                lineReplyService.replyText(replyToken, ok ? "✅ 已刪除第 " + idx + " 個提醒" : "❌ 找不到第 " + idx + " 個提醒");
            }
            case HELP -> lineReplyService.replyText(replyToken, "可用指令：\n+ 待辦事項\nList\nDone 事項編號\n$金額 備註\nsum\n$list\ntoday\nmonth\nRemind 日期 時間 事項\nRemind list");
            default -> lineReplyService.replyText(replyToken, "我看不懂 😅 可以輸入 functions 看指令");
        }
    }

    private Command fillPendingSlots(Command pending, String userText) {
        if (pending == null || userText == null) return pending;
        String text = userText.trim();
        if (text.isBlank()) return pending;

        // fill only what we were missing (but allow the user to provide multiple at once)
        if (pending.missing.contains("date")) {
            String d = normalizeDateShortcut(text);
            if (d.matches("^\\d{4}-\\d{2}-\\d{2}$")) pending.slots.put("date", d);
        }
        if (pending.missing.contains("time")) {
            String t = normalizeTimeShortcut(text);
            if (t.matches("^\\d{2}:\\d{2}$")) pending.slots.put("time", t);
        }
        if (pending.missing.contains("index")) {
            Integer idx = parseInt(text);
            if (idx != null) pending.slots.put("index", idx);
        }
        if (pending.missing.contains("amount")) {
            Double amt = parseDouble(text);
            if (amt != null) pending.slots.put("amount", amt);
        }
        if (pending.missing.contains("item")) {
            pending.slots.put("item", text);
        }
        if (pending.missing.contains("text")) {
            pending.slots.put("text", text);
        }
        return pending;
    }

    private String normalizeDateShortcut(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
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

    private String normalizeTimeShortcut(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2}):(\\d{2})$")
                .matcher(text.trim());
        if (!m.matches()) return text;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        return String.format("%02d:%02d", h, min);
    }

    private Integer parseInt(String text) {
        try {
            String cleaned = text.trim().replaceAll("[^0-9-]", "");
            if (cleaned.isBlank()) return null;
            return Integer.parseInt(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private Double parseDouble(String text) {
        try {
            String cleaned = text.trim()
                    .replaceAll("[$,]", "")
                    .replaceAll("[^0-9.+-]", "");
            if (cleaned.isBlank()) return null;
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatAmount(double v) {
        // avoid 28.0 style
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return String.format("%.0f", v);
        }
        // trim trailing zeros
        String s = String.format("%.2f", v);
        return s.replaceAll("\\.?0+$", "");
    }
}

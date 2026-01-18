package com.mypa.line_mypersonalassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

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

    public LineBotController(LineReplyService lineReplyService, 
                             TodoService todoService, 
                             ExpenseService expenseService, 
                             ReminderService reminderService, 
                             PendingReminderService pendingReminderService) {
        this.lineReplyService = lineReplyService;
        this.todoService = todoService;
        this.expenseService = expenseService;
        this.reminderService = reminderService;
        this.pendingReminderService = pendingReminderService;
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
                      .append(r.amount >= 0 ? "+" : "").append(r.amount)
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
            

            // 記帳：$-50 午餐 or $+90000 薪水
            if(userText.startsWith("$")) {

                if (!userText.matches("^\\$[+-]\\d+\\s+.+$")) {
                    lineReplyService.replyText(
                            replyToken,
                            "❌ 記帳格式錯誤\n請使用：\n$-50 午餐\n$+90000 薪水"
                    );
                    return "OK";
                }

                String content = userText.substring(1); // "-50 午餐"
                String[] parts = content.split("\\s+", 2);

                int amount = Integer.parseInt(parts[0]);
                String item = parts[1].trim();
                expenseService.addExpense(userId, amount, item);
                String type = amount >= 0 ? "進帳" : "支出";

                lineReplyService.replyText(replyToken, "✅ 已記錄" + type + "\n" + (amount >= 0 ? "+" : "") + amount + " 元\n" + "備註：" + item);
        
                return "OK";
            }

            // 結算：Sum
            if(userText.equalsIgnoreCase("Sum")) {
                int total = expenseService.sum(userId);
                var list = expenseService.list(userId);

                StringBuilder sb = new StringBuilder();
                sb.append("📊 目前累計：").append(total).append("元\n");

                sb.append("🕒 最近 5 筆：\n");
                int start = Math.max(0, list.size() - 5);
                for(int i = start; i< list.size() ; i++) {
                    ExpenseService.Record r = list.get(i);
                    sb.append("- ").append(r.amount).append(" ").append(r.item).append("\n");
                }

                lineReplyService.replyText(replyToken, sb.toString());
                continue;
            }
            
            // 今日清單 + 今日總計
            if (userText.equalsIgnoreCase("today")) {
            
                var list = expenseService.listToday(userId);
                int total = expenseService.sumToday(userId);
            
                if (list.isEmpty()) {
                    lineReplyService.replyText(replyToken, "📅 今天沒有任何紀錄\n今日總計：0");
                    return "OK";
                }
            
                StringBuilder sb = new StringBuilder("📅 今天紀錄：\n");
                int i = 1;
                for (var r : list) {
                    sb.append(i++).append(". ")
                      .append(r.amount >= 0 ? "+" : "").append(r.amount)
                      .append(" ").append(r.item).append("\n");
                    if (i > 10) break; // 先顯示前 10 筆
                }
                sb.append("\n今日總計：").append(total);
            
                lineReplyService.replyText(replyToken, sb.toString());
                return "OK";
            }

            // 本月清單 + 本月總計
            if (userText.equalsIgnoreCase("month")) {
                var list = expenseService.listThisMonth(userId);
                int total = expenseService.sumThisMonth(userId);
            
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

            // 錯誤訊息
            lineReplyService.replyText(replyToken, "我看不懂這個指令 🤔\n\n可用指令：\n+ 待辦事項\nList\nDone 事項編號\n$金額 備註\nsum\n$list\ntoday\nmonth\nRemind 日期 時間 事項\nRemind list");

        }

        return "OK";
    }

}

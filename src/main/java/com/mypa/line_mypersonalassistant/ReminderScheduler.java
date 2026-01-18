package com.mypa.line_mypersonalassistant;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;

@Component
@EnableScheduling
public class ReminderScheduler {

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M/d HH:mm");

    private final ReminderService reminderService;
    private final LinePushService linePushService;
    private final PendingReminderService pendingReminderService;

    public ReminderScheduler(ReminderService reminderService, LinePushService linePushService, PendingReminderService pendingReminderService) {
        this.reminderService = reminderService;
        this.linePushService = linePushService;
        this.pendingReminderService = pendingReminderService;
    }

    // ✅ 每分鐘清一次逾時 pending
    @Scheduled(fixedDelay = 60_000)
    public void cleanupPending() {
        int removed = pendingReminderService.cleanupExpired();
        if (removed > 0) {
            System.out.println("🧹 cleaned expired pending reminders: " + removed);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void tick() {
        var due = reminderService.dueNow();
        for (var r : due) {
            // 把 eventAt 轉回人看得懂的字串
            String eventStr = Instant.ofEpochMilli(r.eventAt).atZone(ZONE).format(FMT);

            linePushService.pushText(r.userId, "⚠️提醒你: " + eventStr + " " + r.text + "!!!!");
            reminderService.markDone(r);
        }
    }
}

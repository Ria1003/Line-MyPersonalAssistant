package com.mypa.line_mypersonalassistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ReminderService {

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

    private final ObjectMapper mapper = new ObjectMapper();
    private final File storageFile = new File("reminders.json");

    private final List<Reminder> reminders = new CopyOnWriteArrayList<>();

    public ReminderService() {
        load();
    }

    public static class Reminder {
        public String userId;

        // 事件時間（例如 1/31 19:00）
        public long eventAt;

        // 提醒時間（例如 1/31 18:59）
        public long remindAt;

        public String text;
        public boolean done;

        public Reminder() {}

        public Reminder(String userId, long eventAt, long remindAt, String text) {
            this.userId = userId;
            this.eventAt = eventAt;
            this.remindAt = remindAt;
            this.text = text;
            this.done = false;
        }
    }

    public void add(String userId, LocalDateTime eventTime, Duration advance, String text) {
        ZonedDateTime eventZdt = eventTime.atZone(ZONE);
        ZonedDateTime remindZdt = eventZdt.minus(advance);

        reminders.add(new Reminder(
                userId,
                eventZdt.toInstant().toEpochMilli(),
                remindZdt.toInstant().toEpochMilli(),
                text
        ));
        save();
    }

    // ✅ 列出未完成、且事件還沒過的提醒（依提醒時間排序）
    public List<Reminder> listUpcoming(String userId) {
        long now = Instant.now().toEpochMilli();

        List<Reminder> out = new ArrayList<>();
        for (Reminder r : reminders) {
            if (r.done) continue;
            if (!Objects.equals(r.userId, userId)) continue;
            if (r.eventAt < now) continue; // 事件已過就不列
            out.add(r);
        }

        out.sort(Comparator.comparingLong(a -> a.remindAt));
        return out;
    }

    // ✅ 刪除第 index 筆（1-based），回傳刪掉的 reminder（沒有就 null）
    public Reminder deleteUpcomingByIndex(String userId, int index1Based) {
        List<Reminder> list = listUpcoming(userId);
        int idx = index1Based - 1;
        if (idx < 0 || idx >= list.size()) return null;

        Reminder target = list.get(idx);

        // 從原始 reminders 列表移除目標
        boolean removed = reminders.remove(target);
        if (removed) save();

        return removed ? target : null;
    }


    public List<Reminder> dueNow() {
        long now = Instant.now().toEpochMilli();
        List<Reminder> out = new ArrayList<>();
        for (Reminder r : reminders) {
            if (!r.done && r.remindAt <= now) out.add(r);
        }
        return out;
    }

    public void markDone(Reminder r) {
        r.done = true;
        save();
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(storageFile, reminders);
        } catch (Exception e) {
            System.out.println("⚠️ reminders save failed: " + e.getMessage());
        }
    }

    private void load() {
        try {
            if (!storageFile.exists()) return;
            List<Reminder> loaded = mapper.readValue(storageFile, new TypeReference<List<Reminder>>() {});
            reminders.clear();
            reminders.addAll(loaded);
        } catch (Exception e) {
            System.out.println("⚠️ reminders load failed: " + e.getMessage());
        }
    }
}

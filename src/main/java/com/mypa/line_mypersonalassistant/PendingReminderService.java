package com.mypa.line_mypersonalassistant;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PendingReminderService {

    // 逾時秒數：5 分鐘
    private static final long TTL_MS = 5 * 60 * 1000;

    private final Map<String, Pending> pendingByUser = new ConcurrentHashMap<>();

    public static class Pending {
        public LocalDateTime eventTime; // 事件時間
        public String text;             // 內容
        public String rawDisplay;       // 方便回覆顯示
        public long createdAtMs;        // pending 建立時間（用來判斷逾時）
    }

    public void put(String userId, Pending p) {
        p.createdAtMs = Instant.now().toEpochMilli();
        pendingByUser.put(userId, p);
    }

    public Pending get(String userId) {
        return pendingByUser.get(userId);
    }

    public Pending remove(String userId) {
        return pendingByUser.remove(userId);
    }

    public boolean has(String userId) {
        return pendingByUser.containsKey(userId);
    }

    // ✅ 逾時清除（給 scheduler 呼叫）
    public int cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        int removed = 0;

        for (var entry : pendingByUser.entrySet()) {
            Pending p = entry.getValue();
            if (p != null && now - p.createdAtMs > TTL_MS) {
                pendingByUser.remove(entry.getKey());
                removed++;
            }
        }
        return removed;
    }
}

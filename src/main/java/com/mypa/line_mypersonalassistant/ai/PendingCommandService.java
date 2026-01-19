package com.mypa.line_mypersonalassistant.ai;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a short-lived pending command per user so we can ask for missing fields
 * (e.g., date/time/index) and then continue without re-classifying the follow-up.
 */
@Service
public class PendingCommandService {
    private static final Duration TTL = Duration.ofMinutes(10);

    private static class Pending {
        Command cmd;
        Instant createdAt;
        Pending(Command cmd) {
            this.cmd = cmd;
            this.createdAt = Instant.now();
        }
    }

    private final Map<String, Pending> byUser = new ConcurrentHashMap<>();

    public void put(String userId, Command cmd) {
        if (userId == null || userId.isBlank() || cmd == null) return;
        byUser.put(userId, new Pending(cmd));
    }

    public Command get(String userId) {
        Pending p = byUser.get(userId);
        if (p == null) return null;
        if (Instant.now().isAfter(p.createdAt.plus(TTL))) {
            byUser.remove(userId);
            return null;
        }
        return p.cmd;
    }

    public void clear(String userId) {
        if (userId == null) return;
        byUser.remove(userId);
    }
}

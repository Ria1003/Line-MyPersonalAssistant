package com.mypa.line_mypersonalassistant.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed command (from rules or AI) that your controller can execute.
 *
 * Notes
 * - "item" is the expense description field (NOT "note").
 * - Use missing[] to drive follow-up questions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Command {
    public Intent intent = Intent.UNKNOWN;
    public Map<String, Object> slots = new HashMap<>();
    public List<String> missing = new ArrayList<>();
    public Double confidence; // 0~1, optional
    public List<Intent> candidates; // optional

    public Command() {}

    public Command(Intent intent) {
        this.intent = intent;
    }

    public static Command unknown() {
        return new Command(Intent.UNKNOWN);
    }

    public Command withSlot(String key, Object value) {
        this.slots.put(key, value);
        return this;
    }

    public Command require(String... keys) {
        for (String k : keys) {
            Object v = this.slots.get(k);
            if (v == null) {
                if (!this.missing.contains(k)) this.missing.add(k);
                continue;
            }
            String sv = String.valueOf(v);
            if (sv.isBlank()) {
                if (!this.missing.contains(k)) this.missing.add(k);
            }
        }
        return this;
    }

    public String slotString(String key) {
        Object v = slots.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Integer slotInt(String key) {
        Object v = slots.get(key);
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Double slotDouble(String key) {
        Object v = slots.get(key);
        if (v == null) return null;
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

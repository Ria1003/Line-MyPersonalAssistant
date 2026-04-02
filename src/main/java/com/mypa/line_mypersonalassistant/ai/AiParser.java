package com.mypa.line_mypersonalassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI-backed natural language parser.
 *
 * This class ONLY converts user input -> {@link Command}.
 * It does NOT execute any action.
 *
 * Environment variables supported:
 * - OPENAI_API_KEY (required)
 * - OPENAI_MODEL (optional, default gpt-4o-mini)
 */
@Service
public class AiParser {
    private static final URI RESPONSES_ENDPOINT = URI.create("https://api.openai.com/v1/responses");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;
    private final String model;
    private final boolean enabled;

    // Useful for debugging in production
    private volatile String lastError = "";

    public AiParser(
            @Value("${openai.api-key:}") String apiKeyFromProps,
            @Value("${openai.model:}") String modelFromProps,
            @Value("${openai.enabled:true}") boolean enabled
    ) {
        String envKey = System.getenv("OPENAI_API_KEY");
        this.apiKey = (apiKeyFromProps != null && !apiKeyFromProps.isBlank()) ? apiKeyFromProps : envKey;

        String envModel = System.getenv("OPENAI_MODEL");
        String chosen = (modelFromProps != null && !modelFromProps.isBlank()) ? modelFromProps : envModel;
        this.model = (chosen == null || chosen.isBlank()) ? "gpt-4o-mini" : chosen;

        // If no key, automatically disable to avoid runtime failures.
        this.enabled = enabled && this.apiKey != null && !this.apiKey.isBlank();
        if (!this.enabled) {
            this.lastError = "OPENAI_API_KEY missing (AI parser disabled)";
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getModel() {
        return model;
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Parse user text into a structured command.
     */
    public Command parse(String userText) {
        if (!enabled) return Command.unknown();
        if (userText == null) return Command.unknown();

        String normalized = userText.trim();
        if (normalized.isEmpty()) return Command.unknown();

        try {
            lastError = "";

            ObjectNode payload = buildPayload(normalized);
            HttpRequest req = HttpRequest.newBuilder(RESPONSES_ENDPOINT)
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                // keep body snippet for debugging but avoid too long
                String body = resp.body();
                if (body != null && body.length() > 500) body = body.substring(0, 500) + "...";
                lastError = "OpenAI HTTP " + resp.statusCode() + (body == null ? "" : (" body=" + body));
                System.err.println("OpenAI error: HTTP " + resp.statusCode() + " body=" + resp.body());
                return Command.unknown();
            }

            String json = extractJsonFromResponsesApi(resp.body());
            if (json == null) {
                lastError = "OpenAI response did not contain JSON";
                return Command.unknown();
            }

            Command cmd = mapper.readValue(json, Command.class);
            if (cmd == null) {
                lastError = "Failed to parse JSON into Command";
                return Command.unknown();
            }

            // Normalize / guardrails
            if (cmd.intent == null) cmd.intent = Intent.UNKNOWN;
            if (cmd.slots == null) cmd.slots = new java.util.HashMap<>();
            if (cmd.missing == null) cmd.missing = new java.util.ArrayList<>();

            // Ensure expense description key name is "item".
            if (cmd.slots.containsKey("note") && !cmd.slots.containsKey("item")) {
                cmd.slots.put("item", cmd.slots.get("note"));
                cmd.slots.remove("note");
            }

            // Recompute missing based on intent requirements
            cmd.missing.clear();
            switch (cmd.intent) {
                case ADD_TODO -> cmd.require("text");
                case DONE_TODO, EXPENSE_DELETE, REMIND_DELETE -> cmd.require("index");
                case ADD_EXPENSE -> cmd.require("amount", "item");
                case REMIND_CREATE -> cmd.require("date", "time", "text");
                default -> {
                }
            }

            return cmd;
        } catch (Exception e) {
            lastError = "AiParser.parse error: " + e.getMessage();
            System.err.println(lastError);
            return Command.unknown();
        }
    }

    private ObjectNode buildPayload(String userText) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);

        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // Strong steering: AI is a parser, not a chat bot.
        String system =
                "You are an intent parser for a LINE chatbot. " +
                "Today's date is " + today + ". " +
                "Convert the user message into ONE JSON object that matches the given JSON schema. " +
                "Do NOT include any extra keys. Do NOT include markdown. Do NOT explain. " +
                "Expense description field must be named item (never note). " +
                "Important rules: " +
                "1) For spending (e.g., 花了, 買了, 付了, 支出, 繳費), set intent=ADD_EXPENSE with a NEGATIVE amount. " +
                "2) For income (e.g., 收到, 薪水, 入帳, 退款, 收入), set intent=ADD_EXPENSE with a POSITIVE amount. " +
                "3) If the message is only a date like 1/18 or 2026-01-18, set intent=UNKNOWN. " +
                "4) If the user says 剛剛/今天, do NOT ask for date. " +
                "5) Example: 我剛剛買菜花了50 => intent=ADD_EXPENSE, amount=-50, item=買菜. " +
                "6) Reminder intent (REMIND_CREATE): triggered by 提醒我/提醒/remind me/remind/set a reminder/set reminder. " +
                "   Always output date as yyyy-MM-dd and time as HH:mm in 24-hour format. " +
                "   If year is not mentioned, choose the nearest future date from today (" + today + "). " +
                "7) Chinese time expressions — convert to 24-hour HH:mm: " +
                "   早上/上午 N 點 = 0N:00 (e.g. 早上8點=08:00, 上午10點=10:00). " +
                "   中午 = 12:00. " +
                "   下午/午後 N 點 = (N+12):00 (e.g. 下午3點=15:00, 下午1點=13:00). " +
                "   晚上/傍晚 N 點 = (N+12):00 (e.g. 晚上7點=19:00, 晚上12點=00:00). " +
                "   N 點半 = N:30 (e.g. 早上8點半=08:30, 下午3點半=15:30). " +
                "   N 點 without prefix: if 1<=N<=6 assume PM (13:00–18:00), else AM. " +
                "8) Reminder examples: " +
                "   提醒我 1/19 15:20喝水 => REMIND_CREATE, date=nearest future 01-19, time=15:20, text=喝水. " +
                "   提醒我 1/24早上8點要po子晴生日文 => REMIND_CREATE, date=nearest future 01-24, time=08:00, text=po子晴生日文. " +
                "   remind me to call mom at 8AM on 1/24 => REMIND_CREATE, date=nearest future 01-24, time=08:00, text=call mom. " +
                "   remind me to po子晴生日文 at 8:00AM on 1/24 => REMIND_CREATE, date=nearest future 01-24, time=08:00, text=po子晴生日文. " +
                "9) Todo examples: " +
                "   '幫我加 買牛奶 到待辦' / 'add call dentist to my list' / '記一下要開會' => ADD_TODO. " +
                "   '我的待辦事項' / '看一下待辦' / 'what are my todos' / 'show my list' => LIST_TODO. " +
                "   '我完成了買牛奶' / 'done with 買牛奶' / 'I finished task 1' / 'mark 1 done' => DONE_TODO. " +
                "10) Expense summary examples: " +
                "   '我今天花了多少' / 'what did I spend today' / '今天花費' => TODAY. " +
                "   '這個月花了多少' / 'this month spending' / '本月消費' => MONTH. " +
                "   '幫我看一下消費清單' / 'show my expenses' / '記帳清單' => EXPENSE_LIST. " +
                "   '目前總共花了多少' / 'total spending' / '花費總計' => EXPENSE_SUM. " +
                "   'I spent 50 dollars on lunch' / '午餐50塊' (no verb) => ADD_EXPENSE, amount=-50, item=lunch. " +
                "11) For REMIND_LIST: '我有什麼提醒' / 'what reminders do I have' / 'list my reminders' => REMIND_LIST. " +
                "    For REMIND_DELETE: 'delete reminder 2' / '刪掉第2個提醒' => REMIND_DELETE, index=2.";

        ObjectNode msg1 = mapper.createObjectNode();
        msg1.put("role", "system");
        msg1.put("content", system);

        ObjectNode msg2 = mapper.createObjectNode();
        msg2.put("role", "user");
        msg2.put("content", userText);

        root.set("input", mapper.createArrayNode().add(msg1).add(msg2));

        // Keep output deterministic.
        root.put("temperature", 0.2);

        // JSON Schema for structured output.
        ObjectNode jsonSchema = mapper.createObjectNode();
        jsonSchema.put("type", "object");
        jsonSchema.put("additionalProperties", false);

        ObjectNode props = mapper.createObjectNode();

        ObjectNode intent = mapper.createObjectNode();
        intent.put("type", "string");
        intent.set("enum", mapper.createArrayNode()
                .add(Intent.ADD_TODO.name())
                .add(Intent.LIST_TODO.name())
                .add(Intent.DONE_TODO.name())
                .add(Intent.ADD_EXPENSE.name())
                .add(Intent.EXPENSE_LIST.name())
                .add(Intent.EXPENSE_SUM.name())
                .add(Intent.EXPENSE_DELETE.name())
                .add(Intent.TODAY.name())
                .add(Intent.MONTH.name())
                .add(Intent.REMIND_CREATE.name())
                .add(Intent.REMIND_LIST.name())
                .add(Intent.REMIND_DELETE.name())
                .add(Intent.HELP.name())
                .add(Intent.UNKNOWN.name()));
        props.set("intent", intent);

        ObjectNode slots = mapper.createObjectNode();
        slots.put("type", "object");
        slots.put("additionalProperties", false);
        ObjectNode slotProps = mapper.createObjectNode();
        slotProps.set("text", mapper.createObjectNode().put("type", "string"));
        slotProps.set("index", mapper.createObjectNode().put("type", "integer"));
        slotProps.set("amount", mapper.createObjectNode().put("type", "number"));
        slotProps.set("item", mapper.createObjectNode().put("type", "string"));
        slotProps.set("date", mapper.createObjectNode().put("type", "string").put("description", "yyyy-MM-dd"));
        slotProps.set("time", mapper.createObjectNode().put("type", "string").put("description", "HH:mm"));
        slots.set("properties", slotProps);
        props.set("slots", slots);

        ObjectNode missing = mapper.createObjectNode();
        missing.put("type", "array");
        missing.set("items", mapper.createObjectNode().put("type", "string"));
        props.set("missing", missing);

        ObjectNode confidence = mapper.createObjectNode();
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        props.set("confidence", confidence);

        jsonSchema.set("properties", props);
        jsonSchema.set("required", mapper.createArrayNode().add("intent").add("slots").add("missing"));

        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");
        format.put("name", "line_bot_command");
        format.put("strict", true);
        format.set("schema", jsonSchema);

        // Responses API expects `text.format`
        ObjectNode text = mapper.createObjectNode();
        text.set("format", format);
        root.set("text", text);

        return root;
    }

    /**
     * Extract the model output JSON from a Responses API response.
     *
     * We intentionally keep this resilient to minor API format changes.
     */
    private String extractJsonFromResponsesApi(String rawBody) throws IOException {
        JsonNode root = mapper.readTree(rawBody);

        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode outItem : output) {
                JsonNode content = outItem.path("content");
                if (!content.isArray()) continue;
                for (JsonNode c : content) {
                    String text = c.path("text").asText(null);
                    if (text != null && !text.isBlank()) return stripToJsonObject(text);
                }
            }
        }

        return stripToJsonObject(rawBody);
    }

    private String stripToJsonObject(String s) {
        if (s == null) return null;
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return s.substring(start, end + 1).trim();
    }
}

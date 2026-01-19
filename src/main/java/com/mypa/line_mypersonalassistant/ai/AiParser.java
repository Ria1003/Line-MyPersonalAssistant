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
 * - OPENAI_MODEL (optional, default gpt-4.1-mini)
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

    public AiParser(
            @Value("${openai.api-key:}") String apiKeyFromProps,
            @Value("${openai.model:}") String modelFromProps,
            @Value("${openai.enabled:true}") boolean enabled
    ) {
        String envKey = System.getenv("OPENAI_API_KEY");
        this.apiKey = (apiKeyFromProps != null && !apiKeyFromProps.isBlank()) ? apiKeyFromProps : envKey;

        String envModel = System.getenv("OPENAI_MODEL");
        String chosen = (modelFromProps != null && !modelFromProps.isBlank()) ? modelFromProps : envModel;
        this.model = (chosen == null || chosen.isBlank()) ? "gpt-4.1-mini" : chosen;

        // If no key, automatically disable to avoid runtime failures.
        this.enabled = enabled && this.apiKey != null && !this.apiKey.isBlank();
    }

    /**
     * Parse user text into a structured command.
     */
    public Command parse(String userText) {
        if (!enabled) return Command.unknown();
        if (userText == null) return Command.unknown();

        // A tiny bit of pre-normalization helps the model.
        String normalized = userText.trim();
        if (normalized.isEmpty()) return Command.unknown();

        try {
            ObjectNode payload = buildPayload(normalized);
            HttpRequest req = HttpRequest.newBuilder(RESPONSES_ENDPOINT)
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                System.err.println("OpenAI error: HTTP " + resp.statusCode() + " body=" + resp.body());
                return Command.unknown();
            }

            String json = extractJsonFromResponsesApi(resp.body());
            if (json == null) return Command.unknown();

            Command cmd = mapper.readValue(json, Command.class);
            if (cmd == null) return Command.unknown();

            // Normalize / guardrails
            if (cmd.intent == null) cmd.intent = Intent.UNKNOWN;
            if (cmd.slots == null) cmd.slots = new java.util.HashMap<>();
            if (cmd.missing == null) cmd.missing = new java.util.ArrayList<>();

            // Ensure expense description key name is "item".
            if (cmd.slots.containsKey("note") && !cmd.slots.containsKey("item")) {
                cmd.slots.put("item", cmd.slots.get("note"));
                cmd.slots.remove("note");
            }

            // Auto-fill missing for certain intents (helps your follow-up flow).
            switch (cmd.intent) {
                case ADD_TODO -> cmd.require("text");
                case DONE_TODO, EXPENSE_DELETE, REMIND_DELETE -> cmd.require("index");
                case ADD_EXPENSE -> cmd.require("amount", "item");
                case REMIND_CREATE -> cmd.require("date", "time", "text");
                default -> {}
            }

            return cmd;
        } catch (Exception e) {
            System.err.println("AiParser.parse error: " + e.getMessage());
            return Command.unknown();
        }
    }

    private ObjectNode buildPayload(String userText) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);

        // Provide strong steering: AI is a parser, not a chat bot.
        String system = "You are an intent parser for a LINE chatbot. " +
                "Convert the user message into ONE JSON object that matches the given JSON schema. " +
                "Do NOT include any extra keys. Do NOT include markdown. Do NOT explain. " +
                "Expense description field must be named item (never note).";

        // Responses API uses input as either a string or array of messages.
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
        ObjectNode schema = mapper.createObjectNode();
        schema.put("name", "line_bot_command");
        schema.put("strict", true);

        ObjectNode jsonSchema = mapper.createObjectNode();
        jsonSchema.put("type", "object");
        jsonSchema.put("additionalProperties", false);

        // intent
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

        // slots
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

        // missing
        ObjectNode missing = mapper.createObjectNode();
        missing.put("type", "array");
        missing.set("items", mapper.createObjectNode().put("type", "string"));
        props.set("missing", missing);

        // confidence
        ObjectNode confidence = mapper.createObjectNode();
        confidence.put("type", "number");
        confidence.put("minimum", 0);
        confidence.put("maximum", 1);
        props.set("confidence", confidence);

        jsonSchema.set("properties", props);
        jsonSchema.set("required", mapper.createArrayNode().add("intent").add("slots").add("missing"));

        schema.set("schema", jsonSchema);

        // ---- Responses API Structured Outputs (json schema) ----
        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");

        // Required by API:
        format.put("name", "line_bot_command");     // ✅ required
        format.put("strict", true);                 // ✅ recommended
        format.set("schema", jsonSchema);           // ✅ required (THIS fixes text.format.schema)

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

        // Preferred path (Responses API): iterate output[*].content[*].text
        // The first output item may be a "reasoning" block, so we scan all.
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode outItem : output) {
                JsonNode content = outItem.path("content");
                if (!content.isArray()) continue;
                for (JsonNode c : content) {
                    // Most common: {"type":"output_text","text":"..."}
                    String text = c.path("text").asText(null);
                    if (text != null && !text.isBlank()) return stripToJsonObject(text);
                }
            }
        }

        // Fallback: search any string fields that look like JSON.
        String asString = rawBody;
        return stripToJsonObject(asString);
    }

    private String stripToJsonObject(String s) {
        if (s == null) return null;
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        return s.substring(start, end + 1).trim();
    }
}

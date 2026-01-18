package com.mypa.line_mypersonalassistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class LineReplyService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String channelAccessToken;
    

    public LineReplyService(@Value("${line.bot.channel-token:}") String channelAccessToken) {
        if (channelAccessToken == null || channelAccessToken.isBlank()) {
            throw new IllegalStateException(
                    "Missing config: line.bot.channel-token (Railway env: LINE_BOT_CHANNEL_TOKEN)"
            );
        }
        this.channelAccessToken = channelAccessToken;
    }
    

    public void replyText(String replyToken, String text) {
        String url = "https://api.line.me/v2/bot/message/reply";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(channelAccessToken);

        Map<String, Object> payload = new HashMap<>();
        payload.put("replyToken", replyToken);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "text");
        msg.put("text", text);

        payload.put("messages", List.of(msg));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, request, String.class);
            System.out.println("✅ Reply API status: " + resp.getStatusCode());
            System.out.println("✅ Reply API body: " + resp.getBody());
        } catch (RestClientResponseException e) {
            // 這段超重要：你會直接看到 LINE 回你的錯誤 JSON（例如 token錯、replyToken過期）
            System.out.println("❌ Reply API error status: " + e.getRawStatusCode());
            System.out.println("❌ Reply API error body: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            System.out.println("❌ Reply API exception: " + e.getMessage());
        }
    }
}

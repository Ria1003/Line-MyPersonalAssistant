package com.mypa.line_mypersonalassistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class LinePushService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String channelAccessToken;

    public LinePushService(@Value("${line.bot.channel-token:}") String channelAccessToken) {
        if (channelAccessToken == null || channelAccessToken.isBlank()) {
            throw new IllegalStateException(
                    "Missing config: line.bot.channel-token (Railway env: LINE_BOT_CHANNEL_TOKEN)"
            );
        }
        this.channelAccessToken = channelAccessToken;
    }

    public void pushText(String userId, String text) {
        String url = "https://api.line.me/v2/bot/message/push";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(channelAccessToken);

        Map<String, Object> body = new HashMap<>();
        body.put("to", userId);

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "text");
        msg.put("text", text);

        body.put("messages", List.of(msg));

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(url, req, String.class);
    }
}

package com.mypa.line_mypersonalassistant;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class LinePushService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String channelAccessToken = "cEUUqWfKxU8TXro0mAIbJcq5PYy7nN/uRjXG8sf1AIj0kaCvLS/cku+mPcR7vqKktErawOTROAamlfnWEtnCU4beKRe4L0ksl5mJPMruxczjHvpjGY7NcdBw/Wo+vZulO+iGNHcDQhNE6C7nco1a8QdB04t89/1O/w1cDnyilFU=";

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

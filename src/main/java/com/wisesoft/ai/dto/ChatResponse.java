package com.wisesoft.ai.dto;

import lombok.Data;

/**
 * 聊天响应 DTO（SSE 事件）
 *
 * @author yuanke
 */
@Data
public class ChatResponse {
    private String type;       // token / done / error
    private String content;
    private String sessionId;

    public static ChatResponse token(String content, String sessionId) {
        ChatResponse r = new ChatResponse();
        r.setType("token");
        r.setContent(content);
        r.setSessionId(sessionId);
        return r;
    }

    public static ChatResponse done(String sessionId) {
        ChatResponse r = new ChatResponse();
        r.setType("done");
        r.setSessionId(sessionId);
        return r;
    }

    public static ChatResponse error(String message, String sessionId) {
        ChatResponse r = new ChatResponse();
        r.setType("error");
        r.setContent(message);
        r.setSessionId(sessionId);
        return r;
    }
}
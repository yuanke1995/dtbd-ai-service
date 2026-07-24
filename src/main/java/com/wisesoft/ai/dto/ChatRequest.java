package com.wisesoft.ai.dto;

import lombok.Data;

/**
 * 聊天请求 DTO
 *
 * @author yuanke
 */
@Data
public class ChatRequest {
    private String sessionId;
    private String question;
}
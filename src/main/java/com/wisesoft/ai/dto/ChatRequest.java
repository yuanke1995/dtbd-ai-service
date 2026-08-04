package com.wisesoft.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求 DTO
 *
 * @author yuanke
 */
@Data
public class ChatRequest {
    private String sessionId;

    @NotBlank(message = "请输入问题")
    private String question;
}

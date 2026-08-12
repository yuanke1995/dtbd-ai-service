package com.wisesoft.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

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

    /** 用户上传图片（data URL，如 data:image/jpeg;base64,xxx） */
    private List<String> images;
}

package com.wisesoft.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项 DTO
 *
 * @author yuanke
 */
@Data
public class SessionInfo {

    private String id;
    private String title;
    private Integer messageCount;
    private LocalDateTime updateTime;
}

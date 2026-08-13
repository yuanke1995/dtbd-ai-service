package com.wisesoft.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项 DTO
 *
 * @author yuanke
 */
@Data
@Schema(description = "会话信息")
public class SessionInfo {

    @Schema(description = "会话 ID")
    private String id;
    @Schema(description = "会话标题")
    private String title;
    @Schema(description = "消息数量")
    private Integer messageCount;
    @Schema(description = "最后更新时间")
    private LocalDateTime updateTime;
}

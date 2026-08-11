package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 问答日志表（用于数据闭环：无命中分析/热门问题）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_qa_log")
public class AiQaLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String sessionId;

    private String question;

    /** 回答摘要（前 500 字） */
    private String answerSummary;

    /** 命中文档 ID 列表（逗号分隔） */
    private String hitDocIds;

    /** 是否有引用标注 */
    private Integer hasCitation;

    /** 回答耗时(ms) */
    private Integer elapsedMs;

    private LocalDateTime createdAt;
}

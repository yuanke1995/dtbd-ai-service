package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 消息表实体
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_message")
public class AiMessage {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话ID */
    private String sessionId;

    /** 角色: user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 思考过程全文（深度思考） */
    private String thinking;

    /** 关联图片URL (JSON数组字符串) */
    private String images;

    /** 引用来源 (JSON数组字符串: [{ref,knowledgeId,docId,fileName,title,snippet}]) */
    private String sources;

    /** 消息序号 (会话内递增) */
    private Integer sequence;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}

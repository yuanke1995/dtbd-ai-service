package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 知识片段表
 * 文本元数据存 MySQL，向量存 Redis（vectorId 关联）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_knowledge")
public class AiKnowledge {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属文档ID */
    private String docId;

    /** 片段标题 */
    private String title;

    /** 片段正文 */
    private String content;

    /** 片段序号 */
    private Integer chunkIndex;

    /** Redis 向量库中的文档ID */
    private String vectorId;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /** 关联图片URL列表(JSON数组字符串)，如 ["/ai/images/xxx/1.png", ...] */
    private String images;

    /** 片段序号 */
    private Integer chunkIndex;

    /** Redis 向量库中的文档ID */
    private String vectorId;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;

    // ===== 关键词检索临时统计（非表字段） =====

    /** 命中的词元数 */
    @TableField(exist = false)
    private Integer hitTerms;

    /** 总词元数 */
    @TableField(exist = false)
    private Integer totalTerms;

    /** 标题是否命中 */
    @TableField(exist = false)
    private boolean titleHit;

    /** 关键词加权分（tf×idf 归一化到 0~1，A3 检索排序用） */
    @TableField(exist = false)
    private double kwScore;
}
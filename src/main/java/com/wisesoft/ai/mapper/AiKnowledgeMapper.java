package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiKnowledge;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 知识片段 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiKnowledgeMapper extends BaseMapper<AiKnowledge> {
}
package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 文档 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiDocumentMapper extends BaseMapper<AiDocument> {
}
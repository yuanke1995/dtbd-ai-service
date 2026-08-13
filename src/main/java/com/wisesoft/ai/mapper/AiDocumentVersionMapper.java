package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiDocumentVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 文档版本快照 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiDocumentVersionMapper extends BaseMapper<AiDocumentVersion> {
}

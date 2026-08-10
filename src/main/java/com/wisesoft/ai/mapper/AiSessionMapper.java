package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiSessionMapper extends BaseMapper<AiSession> {
}

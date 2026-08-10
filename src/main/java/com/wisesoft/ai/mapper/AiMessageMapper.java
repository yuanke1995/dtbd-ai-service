package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 消息 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}

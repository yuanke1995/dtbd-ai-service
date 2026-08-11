package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiQaLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 问答日志 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiQaLogMapper extends BaseMapper<AiQaLog> {
}

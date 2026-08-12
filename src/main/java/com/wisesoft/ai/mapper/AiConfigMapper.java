package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型配置表 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfig> {
}

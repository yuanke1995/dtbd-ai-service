package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiAnswerCache;
import org.apache.ibatis.annotations.Mapper;

/**
 * 相似问题答案缓存 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiAnswerCacheMapper extends BaseMapper<AiAnswerCache> {
}
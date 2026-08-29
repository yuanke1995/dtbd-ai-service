package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * AI 消息 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /** 按 ID 查询（忽略逻辑删除标记，撤销删除时定位已软删消息用；自定义 SQL 不经 @TableLogic 改写） */
    @Select("SELECT * FROM c_ai_message WHERE id = #{id}")
    AiMessage selectByIdIgnoreDeleted(@Param("id") String id);

    /** 按会话+序号+角色查询（忽略逻辑删除，撤销删除时配对同组用户问题用） */
    @Select("SELECT * FROM c_ai_message WHERE session_id = #{sessionId} AND sequence = #{seq} AND role = #{role} " +
            "ORDER BY create_time DESC LIMIT 1")
    AiMessage selectBySeqIgnoreDeleted(@Param("sessionId") String sessionId,
                                       @Param("seq") int seq,
                                       @Param("role") String role);

    /** 恢复单条软删除消息（撤销删除用） */
    @Update("UPDATE c_ai_message SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") String id);
}
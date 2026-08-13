package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiKnowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * AI 知识片段 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiKnowledgeMapper extends BaseMapper<AiKnowledge> {

    /**
     * 物理删除某文档的全部知识块（版本回滚时释放主键，以便按快照原 id 重建）
     */
    @org.apache.ibatis.annotations.Delete("DELETE FROM c_ai_knowledge WHERE doc_id = #{docId}")
    int physicalDeleteByDocId(@Param("docId") String docId);
}

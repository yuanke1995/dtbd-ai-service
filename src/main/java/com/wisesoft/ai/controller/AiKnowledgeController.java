package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.service.QaLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识块管理（新增/查询），以及无命中问题查询
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiKnowledgeController {

    private final AiKnowledgeMapper knowledgeMapper;
    private final QaLogService qaLogService;

    /**
     * 获取无命中问题列表（按频次降序），供一键入库使用
     */
    @GetMapping("/knowledge/unmatched")
    public ResultJson listUnmatched() {
        return ResultJson.ok(qaLogService.listUnmatched());
    }

    /**
     * 新增知识块（手动补充知识库缺口）
     * body: {"title":"问题","content":"回答内容","docId":"可选关联文档ID"}
     */
    @PostMapping("/knowledge")
    public ResultJson createKnowledge(@RequestBody Map<String, Object> body) {
        String title = body.get("title") == null ? null : String.valueOf(body.get("title")).trim();
        String content = body.get("content") == null ? null : String.valueOf(body.get("content")).trim();
        String docId = body.get("docId") == null ? null : String.valueOf(body.get("docId")).trim();

        if (title == null || title.isBlank()) {
            throw new BizException("标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new BizException("内容不能为空");
        }

        AiKnowledge k = new AiKnowledge();
        k.setTitle(title);
        k.setContent(content);
        k.setDocId(docId != null && !docId.isBlank() ? docId : null);
        // 无向量，依靠关键词检索召回
        k.setVectorId(null);
        knowledgeMapper.insert(k);
        return ResultJson.ok(Map.of("id", k.getId()), "知识块已创建");
    }
}
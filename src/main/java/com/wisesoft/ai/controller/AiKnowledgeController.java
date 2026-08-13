package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.service.DocumentService;
import com.wisesoft.ai.service.QaLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final AiDocumentMapper documentMapper;
    private final DocumentService documentService;
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
        if (title.length() > 200) {
            throw new BizException("标题过长（最多200字）");
        }
        if (content == null || content.isBlank()) {
            throw new BizException("内容不能为空");
        }

        // H3：docId 存在性校验（填了无效 docId 会导致检索 inSql 排除、知识块入死区）
        if (docId != null && !docId.isBlank()) {
            Long cnt = documentMapper.selectCount(new LambdaQueryWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId).eq(AiDocument::getStatus, 0));
            if (cnt == null || cnt == 0) {
                throw new BizException("关联文档不存在或已停用");
            }
        }

        AiKnowledge k = new AiKnowledge();
        k.setTitle(title);
        k.setContent(content);
        k.setDocId(docId != null && !docId.isBlank() ? docId : null);
        // 先入库，再生成向量（H1：失败降级仅关键词召回，不阻断）
        knowledgeMapper.insert(k);
        documentService.embedAndStore(k, k.getTitle() + "\n" + k.getContent());
        return ResultJson.ok(Map.of("id", k.getId()), "知识块已创建");
    }
}
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
@Tag(name = "知识库", description = "无命中问题查询、知识块预览、手动创建知识块")
public class AiKnowledgeController {

    private final AiKnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;
    private final DocumentService documentService;
    private final QaLogService qaLogService;

    @Operation(summary = "无命中问题列表", description = "获取最近 30 天内无检索命中的问题列表（按频次降序，最多 50 条），供一键入库补齐知识缺口")
    @GetMapping("/knowledge/unmatched")
    public ResultJson listUnmatched() {
        return ResultJson.ok(qaLogService.listUnmatched());
    }

    @Operation(summary = "按文档列知识块", description = "获取指定文档下的所有知识块（含标题、正文、图片），用于知识块预览")
    @GetMapping("/knowledge/list")
    public ResultJson listByDoc(
            @Parameter(description = "文档 ID") @RequestParam("docId") String docId) {
        if (docId == null || docId.isBlank()) {
            throw new BizException("缺少 docId");
        }
        List<Map<String, Object>> list = knowledgeMapper.selectList(
                        new LambdaQueryWrapper<AiKnowledge>()
                                .eq(AiKnowledge::getDocId, docId)
                                .orderByAsc(AiKnowledge::getChunkIndex))
                .stream().map(k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", k.getId());
                    m.put("title", k.getTitle());
                    m.put("chunkIndex", k.getChunkIndex());
                    m.put("content", k.getContent());
                    m.put("images", k.getImages() != null && !k.getImages().isBlank()
                            ? com.alibaba.fastjson2.JSON.parseArray(k.getImages()) : List.of());
                    return m;
                }).toList();
        return ResultJson.ok(list);
    }

    @Operation(summary = "新增知识块", description = "手动创建知识块（自动生成向量），用于补充知识库缺口。可关联已有文档或独立创建")
    @PostMapping("/knowledge")
    public ResultJson createKnowledge(
            @Parameter(description = "{\"title\": \"问题/标题\", \"content\": \"回答内容\", \"docId\": \"可选关联文档ID\"}")
            @RequestBody Map<String, Object> body) {
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

    @Operation(summary = "编辑知识块", description = "修改知识块标题/正文（图片保留），并重新向量化")
    @PutMapping("/knowledge/{id}")
    public ResultJson updateKnowledge(
            @Parameter(description = "知识块 ID") @PathVariable("id") String id,
            @Parameter(description = "{\"title\": \"新标题\", \"content\": \"新内容\"}")
            @RequestBody Map<String, String> body) {
        documentService.updateKnowledge(id, body.get("title"), body.get("content"));
        return ResultJson.ok("知识块已更新");
    }

    @Operation(summary = "删除知识块", description = "删除知识块（同步移除向量并扣减文档知识块数）")
    @DeleteMapping("/knowledge/{id}")
    public ResultJson deleteKnowledge(
            @Parameter(description = "知识块 ID") @PathVariable("id") String id) {
        documentService.deleteKnowledge(id);
        return ResultJson.ok("知识块已删除");
    }
}
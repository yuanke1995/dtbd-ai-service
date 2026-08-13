package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.service.DocumentMetaCache;
import com.wisesoft.ai.service.HybridRetrievalService;
import com.wisesoft.ai.service.RerankService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 检索调试：分步展示"关键词命中 → 向量命中 → 合并 → 重排 → 最终上下文 → 被排除"，
 * 用于排查"为什么答非所问"（P0-3）
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/debug")
@RequiredArgsConstructor
public class RetrievalDebugController {

    /** 与 RagService 保持一致 */
    private static final int RERANK_MIN = 6;
    private static final int RERANK_MAX = 15;
    private static final int MAX_CONTEXT_HITS = 8;

    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;

    @PostMapping("/retrieval")
    public ResultJson debug(@RequestBody Map<String, String> body) {
        String query = body.get("question");
        if (query == null || query.isBlank()) {
            throw new BizException("请输入问题");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);

        // 1. 关键词命中（含命中率/标题命中）
        List<AiKnowledge> kwDocs = hybridRetrievalService.keywordSearch(query);
        result.put("keywordHits", kwDocs.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knowledgeId", k.getId());
            m.put("title", k.getTitle());
            m.put("docName", documentMetaCache.getFileName(k.getDocId()));
            m.put("hitRate", k.getTotalTerms() > 0
                    ? Math.round(k.getHitTerms() * 100.0 / k.getTotalTerms()) / 100.0 : 0);
            m.put("titleHit", k.isTitleHit());
            m.put("snippet", snippet(k.getContent()));
            return m;
        }).toList());

        // 2. 向量命中（相似度分）
        List<Document> vectorDocs = hybridRetrievalService.vectorSearch(query);
        result.put("vectorHits", vectorDocs.stream().map(doc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knowledgeId", doc.getId());
            m.put("title", String.valueOf(doc.getMetadata().getOrDefault("title", "")));
            m.put("docName", documentMetaCache.getFileName(String.valueOf(doc.getMetadata().get("docId"))));
            m.put("score", doc.getScore() == null ? 0 : Math.round(doc.getScore() * 100.0) / 100.0);
            m.put("snippet", snippet(doc.getText()));
            return m;
        }).toList());

        // 3. 合并（RagService 实际使用的检索结果）
        List<HybridRetrievalService.Hit> merged = hybridRetrievalService.search(query);
        result.put("merged", merged.stream().map(this::hitMap).toList());

        // 4. 重排（与 RagService 相同条件：6~15 条才重排）
        List<HybridRetrievalService.Hit> reranked = merged;
        if (merged.size() > RERANK_MIN && merged.size() <= RERANK_MAX) {
            reranked = rerankService.rank(merged, query);
            result.put("rerankApplied", true);
        } else {
            result.put("rerankApplied", false);
            result.put("rerankSkipReason", merged.size() <= RERANK_MIN ? "命中过少（≤" + RERANK_MIN + "）无需重排"
                    : "命中过多（>" + RERANK_MAX + "）不重排");
        }
        result.put("reranked", reranked.stream().map(this::hitMap).toList());

        // 5. 最终上下文（截断 8）+ 被排除
        List<HybridRetrievalService.Hit> finalTop = reranked.size() > MAX_CONTEXT_HITS
                ? reranked.subList(0, MAX_CONTEXT_HITS) : reranked;
        result.put("finalContext", finalTop.stream().map(this::hitMap).toList());
        if (reranked.size() > MAX_CONTEXT_HITS) {
            result.put("excluded", reranked.subList(MAX_CONTEXT_HITS, reranked.size())
                    .stream().map(this::hitMap).toList());
        } else {
            result.put("excluded", List.of());
        }
        return ResultJson.ok(result);
    }

    private Map<String, Object> hitMap(HybridRetrievalService.Hit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("knowledgeId", h.knowledgeId());
        m.put("title", h.title());
        m.put("docName", documentMetaCache.getFileName(h.docId()));
        m.put("score", Math.round(h.score() * 100.0) / 100.0);
        m.put("snippet", snippet(h.content()));
        return m;
    }

    private String snippet(String content) {
        if (content == null) return "";
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}

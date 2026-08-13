package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiKnowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 混合检索：向量召回 + MySQL 关键词召回 并行执行 → 按 knowledgeId 合并去重 → 规则加权排序
 * - 向量：topK 放大（默认 15）+ 阈值放宽（0.3），提高召回率
 * - 关键词：词元 LIKE 匹配（自动排除已弃用文档），命中词元越多权重越高
 * - 融合分 = 0.6 * 向量相似度 + 0.4 * 关键词命中率 + 标题奖励
 * 解决"评分组件"等长尾词向量检索不到的问题
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private static final double VECTOR_WEIGHT = 0.6;
    private static final double KEYWORD_WEIGHT = 0.4;
    private static final double TITLE_BONUS = 0.1;
    /** 关键词检索超时（ms） */
    private static final long KEYWORD_TIMEOUT_MS = 800;
    /** 关键词召回上限 */
    private static final int KEYWORD_LIMIT = 20;

    private final VectorStore vectorStore;
    private final AiKnowledgeMapper knowledgeMapper;
    private final AiAppProperties properties;
    private final KeywordExtractor keywordExtractor;

    /**
     * 混合检索结果
     */
    public record Hit(String knowledgeId, String docId, String title, String content,
                      List<String> images, double score) {
    }

    /**
     * 混合检索：返回按融合分降序的结果（已过滤弃用文档）
     */
    public List<Hit> search(String query) {
        // 1. 向量召回（放大召回率）
        List<Document> vectorDocs = vectorSearch(query);

        // 2. 关键词召回（并行，超时兜底）
        List<AiKnowledge> kwDocs = keywordSearch(query);

        // 3. 合并去重 + 加权排序
        Map<String, Hit> merged = new LinkedHashMap<>();
        Set<String> kwHit = kwDocs.stream().map(AiKnowledge::getId).collect(Collectors.toSet());

        // 向量命中：score = 0.6 * 向量分
        for (Document doc : vectorDocs) {
            double vecScore = parseScore(doc.getScore());
            double score = VECTOR_WEIGHT * vecScore;
            String kid = String.valueOf(doc.getId());
            merged.put(kid, buildHit(doc, kid, score));
        }
        // 关键词命中：score = 0.6 * 0(无向量分) + 0.4 * 命中率 + 标题奖励
        for (AiKnowledge k : kwDocs) {
            double hitRate = k.getHitTerms() / (double) Math.max(1, k.getTotalTerms());
            double score = KEYWORD_WEIGHT * hitRate
                    + (k.isTitleHit() ? TITLE_BONUS : 0);
            // merge 时保留非空 docId（向量 metadata 可能被 M6 丢弃导致 docId 为空，关键词版是正确的）
            merged.merge(k.getId(), buildHit(k, score), (oldHit, newHit) ->
                    new Hit(oldHit.knowledgeId(),
                            oldHit.docId() == null || oldHit.docId().isBlank() ? newHit.docId() : oldHit.docId(),
                            oldHit.title(), oldHit.content(), oldHit.images(),
                            Math.max(oldHit.score(), newHit.score())));
        }

        List<Hit> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result;
    }

    /**
     * 向量召回（独立方法，供检索调试复用）
     */
    public List<Document> vectorSearch(String query) {
        try {
            SearchRequest req = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(15, properties.getRetrieval().getTopK()))
                    .similarityThreshold(Math.min(0.3, properties.getRetrieval().getSimilarityThreshold()))
                    .build();
            return vectorStore.similaritySearch(req);
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索：词元 OR LIKE（content/title），自动排除弃用文档；带命中统计
     */
    public List<AiKnowledge> keywordSearch(String query) {
        List<String> terms = keywordExtractor.extract(query);
        if (terms.isEmpty()) return List.of();
        try {
            return CompletableFuture.supplyAsync(() -> {
                // WHERE doc_id IN (生效文档) AND ((content LIKE ? OR title LIKE ?) OR ...)
                QueryWrapper<AiKnowledge> wrapper = new QueryWrapper<AiKnowledge>()
                        .and(w -> w.inSql("doc_id", "SELECT id FROM c_ai_document WHERE status=0 AND deleted=0")
                                .or().isNull("doc_id"));
                wrapper.and(w -> {
                    for (int i = 0; i < terms.size(); i++) {
                        if (i > 0) w.or();
                        String term = terms.get(i);
                        w.and(t -> t.like("content", term).or().like("title", term));
                    }
                });
                wrapper.last("LIMIT " + KEYWORD_LIMIT);
                List<AiKnowledge> hits = knowledgeMapper.selectList(wrapper);
                if (hits.isEmpty()) return hits;
                // 统计每个词元是否命中（用于计算命中率）
                for (AiKnowledge k : hits) {
                    int hit = 0;
                    for (String term : terms) {
                        if ((k.getContent() != null && k.getContent().contains(term))
                                || (k.getTitle() != null && k.getTitle().contains(term))) {
                            hit++;
                        }
                    }
                    k.setHitTerms(hit);
                    k.setTotalTerms(terms.size());
                    k.setTitleHit(k.getTitle() != null && terms.stream().anyMatch(k.getTitle()::contains));
                }
                return hits;
            }).get(KEYWORD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("关键词检索失败/超时: {}", e.getMessage());
            return List.of();
        }
    }

    private double parseScore(Double score) {
        return score == null ? 0 : score;
    }

    private Hit buildHit(Document doc, String kid, double score) {
        Map<String, Object> md = doc.getMetadata();
        String docId = md.get("docId") == null ? "" : String.valueOf(md.get("docId"));
        String title = md.get("title") == null ? "" : String.valueOf(md.get("title"));
        List<String> images = imagesFromMd(md);
        // M6 RedisVectorStore 可能丢弃 metadata（docId/title/images 均可能为空），按 knowledgeId 查 MySQL 兜底
        if (kid != null && (docId.isEmpty() || title.isEmpty() || images.isEmpty())) {
            try {
                AiKnowledge k = knowledgeMapper.selectById(kid);
                if (k != null) {
                    if (docId.isEmpty()) docId = String.valueOf(k.getDocId());
                    if (title.isEmpty()) title = k.getTitle();
                    if (images.isEmpty() && k.getImages() != null && !k.getImages().isBlank()) {
                        images = com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
                    }
                }
            } catch (Exception e) {
                log.warn("查询知识块元数据失败: {}", e.getMessage());
            }
        }
        return new Hit(kid, docId, title, doc.getText(), images, score);
    }

    private Hit buildHit(AiKnowledge k, double score) {
        List<String> images = new ArrayList<>();
        if (k.getImages() != null && !k.getImages().isBlank()) {
            try {
                images = com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
            } catch (Exception e) {
                images = List.of();
            }
        }
        return new Hit(String.valueOf(k.getId()), String.valueOf(k.getDocId()),
                k.getTitle(), k.getContent(), images, score);
    }

    private List<String> imagesFromMd(Map<String, Object> md) {
        Object v = md.get("images");
        if (v == null) return List.of();
        try {
            return com.alibaba.fastjson2.JSON.parseArray(String.valueOf(v), String.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}

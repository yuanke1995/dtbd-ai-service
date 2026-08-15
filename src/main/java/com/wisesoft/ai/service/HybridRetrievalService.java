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
 * 混合检索：向量召回 + MySQL 关键词召回 并行执行 → 按 knowledgeId 合并去重 → 加权排序
 * <p>
 * - 向量：topK 放大 + 阈值放宽（0.3），分数归一化到 0~1（(score-0.3)/(1-0.3)）
 * - 关键词：词元 LIKE 召回 + 词频加权（tf×idf，标题词频×2，归一化 0~1）
 * - 融合：双命中**叠加**（向量分 + 关键词分 + 标题奖励），单路命中取各自权重分
 * - 位置：文档首块（chunkIndex=0）小幅加分，中部块相对降权
 * 权重来自 DB 配置 retrieval.*（设置页保存即生效；默认 0.6/0.4/0.1）
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    /** 关键词检索超时（ms） */
    private static final long KEYWORD_TIMEOUT_MS = 800;
    /** 关键词召回上限 */
    private static final int KEYWORD_LIMIT = 20;
    /** 向量检索相似度阈值（与归一化基准一致：0.3 → 0 分，1.0 → 1 分） */
    private static final double VEC_THRESHOLD = 0.3;

    private final VectorStore vectorStore;
    private final AiKnowledgeMapper knowledgeMapper;
    private final AiAppProperties properties;
    private final KeywordExtractor keywordExtractor;
    private final ConfigService configService;

    /**
     * 混合检索结果（chunkIndex 用于位置奖励）
     */
    public record Hit(String knowledgeId, String docId, String title, String content,
                      List<String> images, double score, Integer chunkIndex) {
    }

    /**
     * 混合检索：返回按融合分降序的结果（已过滤弃用文档）
     */
    public List<Hit> search(String query) {
        // 权重动态读取（DB 配置，保存即生效；缺失时兜底 yml 默认值 0.6/0.4/0.1）
        double vectorWeight = configService.getDouble("retrieval.vectorWeight");
        double keywordWeight = configService.getDouble("retrieval.keywordWeight");
        double titleBonus = configService.getDouble("retrieval.titleBonus");

        // 1. 向量召回（放大召回率）
        List<Document> vectorDocs = vectorSearch(query);

        // 2. 关键词召回（并行，超时兜底）
        List<AiKnowledge> kwDocs = keywordSearch(query);

        // 3. 合并去重 + 加权（A1：双命中叠加，不再取 max）
        Map<String, Hit> merged = new LinkedHashMap<>();
        Set<String> kwHit = kwDocs.stream().map(AiKnowledge::getId).collect(Collectors.toSet());

        // 向量命中：score = 向量权重 × 归一化向量分
        for (Document doc : vectorDocs) {
            double vecScore = parseScore(doc.getScore());
            double vecNorm = Math.max(0, (vecScore - VEC_THRESHOLD) / (1.0 - VEC_THRESHOLD));
            double score = vectorWeight * vecNorm;
            String kid = String.valueOf(doc.getId());
            merged.put(kid, buildHit(doc, kid, score));
        }
        // 关键词命中：score = 关键词权重 × 词频加权分 + 标题奖励；与向量命中叠加（相加）
        for (AiKnowledge k : kwDocs) {
            double hitRate = k.getKwScore(); // 词频加权归一化分（0~1，替代原词元占比）
            double score = keywordWeight * hitRate
                    + (k.isTitleHit() ? titleBonus : 0);
            merged.merge(k.getId(), buildHit(k, score), (oldHit, newHit) ->
                    new Hit(oldHit.knowledgeId(),
                            oldHit.docId() == null || oldHit.docId().isBlank() ? newHit.docId() : oldHit.docId(),
                            oldHit.title(), oldHit.content(), oldHit.images(),
                            oldHit.score() + newHit.score(), // A1：双命中叠加
                            oldHit.chunkIndex() == null ? newHit.chunkIndex() : oldHit.chunkIndex()));
        }

        // A5：位置奖励（排序前统一加，保证分数与顺序一致）
        List<Hit> result = new ArrayList<>(merged.values());
        for (int i = 0; i < result.size(); i++) {
            Hit h = result.get(i);
            double bonus = positionBonus(h.chunkIndex());
            if (bonus != 0) {
                result.set(i, new Hit(h.knowledgeId(), h.docId(), h.title(), h.content(), h.images(),
                        h.score() + bonus, h.chunkIndex()));
            }
        }
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result;
    }

    /** 多路并行检索线程池（daemon，供深度思考多路检索用） */
    private final ExecutorService multiSearchPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "multi-search");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PreDestroy
    void shutdownMultiSearch() {
        multiSearchPool.shutdownNow();
    }

    /**
     * 多路并行检索（深度思考用）：多个 query 并行调用 search()，按 knowledgeId 合并去重，保留最高分
     * 单 query 直接委托 search()；并行总超时 8s（超时用已完成结果）
     */
    public List<Hit> searchMulti(List<String> queries) {
        if (queries == null || queries.isEmpty()) return List.of();
        List<String> qs = queries.stream().map(String::trim).filter(q -> !q.isBlank()).distinct().toList();
        if (qs.size() <= 1) {
            return qs.isEmpty() ? List.of() : search(qs.get(0));
        }
        try {
            List<CompletableFuture<List<Hit>>> futures = qs.stream()
                    .map(q -> CompletableFuture.supplyAsync(() -> search(q), multiSearchPool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(8000, TimeUnit.MILLISECONDS);
            // 合并：同一 knowledgeId 保留 score 最高者（跨 query 分数同体系可直接 max）
            Map<String, Hit> merged = new LinkedHashMap<>();
            for (CompletableFuture<List<Hit>> f : futures) {
                List<Hit> hits = f.isDone() ? f.getNow(List.of()) : List.of();
                for (Hit h : hits) {
                    merged.merge(h.knowledgeId(), h, (a, b) -> a.score() >= b.score() ? a : b);
                }
            }
            List<Hit> result = new ArrayList<>(merged.values());
            result.sort((a, b) -> Double.compare(b.score(), a.score()));
            return result;
        } catch (Exception e) {
            log.warn("多路检索失败，降级首路: {}", e.getMessage());
            return search(qs.get(0));
        }
    }

    /**
     * 向量召回（独立方法，供检索调试复用）
     */
    public List<Document> vectorSearch(String query) {
        try {
            SearchRequest req = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(15, properties.getRetrieval().getTopK()))
                    .similarityThreshold(Math.min(VEC_THRESHOLD, properties.getRetrieval().getSimilarityThreshold()))
                    .build();
            return vectorStore.similaritySearch(req);
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索：词元 OR LIKE（content/title），自动排除弃用文档；
     * 命中后按词频加权（tf×idf，标题词频×2）归一化到 kwScore（0~1）
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
                return scoreKeywordHits(hits, terms);
            }).get(KEYWORD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("关键词检索失败/超时: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 词频加权打分（A3）：tf×idf，标题词频×2，min(tf,3) 封顶防极端词频；命中集内归一化到 0~1
     */
    private List<AiKnowledge> scoreKeywordHits(List<AiKnowledge> hits, List<String> terms) {
        // 词元在命中集内的文档频率（IDF 用）
        Map<String, Integer> df = new HashMap<>();
        for (AiKnowledge k : hits) {
            for (String term : terms) {
                if (countOccurrences(k.getContent(), term) > 0 || countOccurrences(k.getTitle(), term) > 0) {
                    df.merge(term, 1, Integer::sum);
                }
            }
        }
        double maxScore = 0;
        for (AiKnowledge k : hits) {
            int hitTermCount = 0;
            double score = 0;
            for (String term : terms) {
                int tf = countOccurrences(k.getContent(), term) + 2 * countOccurrences(k.getTitle(), term);
                if (tf == 0) continue;
                hitTermCount++;
                double idf = Math.log(1 + (double) hits.size() / Math.max(1, df.getOrDefault(term, 1)));
                score += Math.min(tf, 3) * idf;
            }
            k.setHitTerms(hitTermCount);
            k.setTotalTerms(terms.size());
            k.setTitleHit(k.getTitle() != null && terms.stream().anyMatch(k.getTitle()::contains));
            k.setKwScore(score);
            maxScore = Math.max(maxScore, score);
        }
        // 归一化 0~1（最大加权分映射为 1）
        for (AiKnowledge k : hits) {
            k.setKwScore(maxScore > 0 ? k.getKwScore() / maxScore : 0);
        }
        return hits;
    }

    /**
     * 统计子串出现次数（不重叠）
     */
    private int countOccurrences(String text, String term) {
        if (text == null || text.isEmpty() || term == null || term.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0) {
            count++;
            idx += term.length();
        }
        return count;
    }

    /**
     * 分块位置奖励（A5）：文档首块小幅加分，靠近开头微加，中部不奖励（相对降权）
     */
    private double positionBonus(Integer chunkIndex) {
        if (chunkIndex == null) return 0;
        if (chunkIndex == 0) return 0.03;
        if (chunkIndex <= 2) return 0.01;
        return 0;
    }

    private double parseScore(Double score) {
        return score == null ? 0 : score;
    }

    private Hit buildHit(Document doc, String kid, double score) {
        Map<String, Object> md = doc.getMetadata();
        String docId = md.get("docId") == null ? "" : String.valueOf(md.get("docId"));
        String title = md.get("title") == null ? "" : String.valueOf(md.get("title"));
        List<String> images = imagesFromMd(md);
        Integer chunkIndex = null;
        // M6 RedisVectorStore 可能丢弃 metadata（docId/title/images 均可能为空），按 knowledgeId 查 MySQL 兜底
        if (kid != null && (docId.isEmpty() || title.isEmpty() || images.isEmpty() || chunkIndex == null)) {
            try {
                AiKnowledge k = knowledgeMapper.selectById(kid);
                if (k != null) {
                    if (docId.isEmpty()) docId = String.valueOf(k.getDocId());
                    if (title.isEmpty()) title = k.getTitle();
                    if (chunkIndex == null) chunkIndex = k.getChunkIndex();
                    if (images.isEmpty() && k.getImages() != null && !k.getImages().isBlank()) {
                        images = com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
                    }
                }
            } catch (Exception e) {
                log.warn("查询知识块元数据失败: {}", e.getMessage());
            }
        }
        return new Hit(kid, docId, title, doc.getText(), images, score, chunkIndex);
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
                k.getTitle(), k.getContent(), images, score, k.getChunkIndex());
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

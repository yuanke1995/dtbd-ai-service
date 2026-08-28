package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
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
 * 两路召回均只返回生效文档（status=0）的知识块：关键词路 SQL 过滤，向量路按命中块的 docId 批量剔除。
 * <p>
 * - 向量：topK 可配（retrieval.vectorTopK，默认 15）+ 阈值放宽（0.3），分数归一化到 0~1（(score-0.3)/(1-0.3)）
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

    /** 默认参数：检索行为参数收口到 c_ai_config（retrieval.*，设置页可调、保存即生效） */
    private long keywordTimeoutMs() { return configService.getInt("retrieval.keywordTimeoutMs", 800); }
    private int keywordLimit() { return configService.getInt("retrieval.keywordLimit", 20); }
    private double vecThreshold() { return configService.getDouble("retrieval.vecThreshold", 0.3); }

    private final VectorStore vectorStore;
    private final AiKnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;
    private final AiAppProperties properties;
    private final KeywordExtractor keywordExtractor;
    private final ConfigService configService;
    private final KeywordIndexService keywordIndexService;

    /**
     * 混合检索结果（chunkIndex 用于位置奖励；titlePath 章节路径，检索侧拼装上下文用）
     */
    public record Hit(String knowledgeId, String docId, String title, String content,
                      List<String> images, double score, Integer chunkIndex, String titlePath) {
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

        // 3. 批量加载向量命中的知识块元数据（一次 selectBatchIds 替代逐条 selectById）+ 不可召回文档集合
        Map<String, AiKnowledge> kidMap = loadKnowledgeBatch(vectorDocs);
        Set<String> blockedDocIds = loadNonRetrievableDocIds(kidMap);

        // 4. 合并去重 + 加权（A1：双命中叠加，不再取 max）
        Map<String, Hit> merged = new LinkedHashMap<>();

        // 向量命中：score = 向量权重 × 归一化向量分；非生效文档（弃用/解析中/解析失败）跳过，与关键词路 status=0 语义一致
        double vt = vecThreshold();
        for (Document doc : vectorDocs) {
            String kid = String.valueOf(doc.getId());
            AiKnowledge k = kidMap.get(kid);
            String docId = k != null && k.getDocId() != null ? String.valueOf(k.getDocId()) : metadataDocId(doc);
            if (docId != null && blockedDocIds.contains(docId)) {
                log.debug("[RAG] 跳过非生效文档命中: docId={} kid={}", docId, kid);
                continue;
            }
            double vecScore = parseScore(doc.getScore());
            double vecNorm = Math.max(0, (vecScore - vt) / (1.0 - vt));
            double score = vectorWeight * vecNorm;
            merged.put(kid, buildHit(doc, k, kid, score));
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
                            oldHit.chunkIndex() == null ? newHit.chunkIndex() : oldHit.chunkIndex(),
                            oldHit.titlePath() == null ? newHit.titlePath() : oldHit.titlePath()));
        }

        // A5：位置奖励（排序前统一加，保证分数与顺序一致）
        List<Hit> result = new ArrayList<>(merged.values());
        for (int i = 0; i < result.size(); i++) {
            Hit h = result.get(i);
            double bonus = positionBonus(h.chunkIndex());
            if (bonus != 0) {
                result.set(i, new Hit(h.knowledgeId(), h.docId(), h.title(), h.content(), h.images(),
                        h.score() + bonus, h.chunkIndex(), h.titlePath()));
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

    /**
     * MySQL 关键词降级检索池：唯一用途是给阻塞式 LIKE 查询套上超时（keywordTimeoutMs）。
     * 必须独立于 multiSearchPool：searchMulti 的外层任务跑在 multiSearchPool 上，其内部
     * 会调到本方法，同池嵌套提交会在 4 个线程被外层占满时让内层任务永远等不到线程（starvation）。
     * 也不用共享池：队列满时共享池的拒绝策略会阻塞调用方，把压力反弹成请求延迟。
     * 本池队列满即 AbortPolicy 拒绝 → 调用方降级为空结果（关键词路本身就是可选增强）。
     */
    private final ThreadPoolExecutor keywordFallbackPool = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16),
            r -> {
                Thread t = new Thread(r, "keyword-mysql");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    @jakarta.annotation.PreDestroy
    void shutdownMultiSearch() {
        multiSearchPool.shutdownNow();
        keywordFallbackPool.shutdownNow();
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
                    .get(configService.getInt("retrieval.searchTimeoutMs", 8000), TimeUnit.MILLISECONDS);
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
                    // topK 直接取配置（默认 15，下限 1）：评估扫参需要小于 15 的值，max(15,...) 钳制会让扫参等价
                    .topK(Math.max(1, configService.getInt("retrieval.vectorTopK", 15)))
                    .similarityThreshold(Math.min(vecThreshold(), properties.getRetrieval().getSimilarityThreshold()))
                    .build();
            return vectorStore.similaritySearch(req);
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索：按 keyword.engine 分派——
     * meilisearch（可用时）走外部索引（中文分词 + 相关度打分）；否则/不可用时降级 MySQL 词元 LIKE。
     * 两条实现返回同一契约：List&lt;AiKnowledge&gt; 且已填充 kwScore/titleHit/hitTerms/totalTerms。
     */
    public List<AiKnowledge> keywordSearch(String query) {
        List<String> terms = keywordExtractor.extract(query);
        if (terms.isEmpty()) return List.of();
        // LIMIT 必须在调用线程求值：supplyAsync 内跑在 commonPool 线程，ThreadLocal 参数覆盖（评估扫参）传不进去
        int limit = keywordLimit();
        if (keywordIndexService.isAvailable()) {
            List<AiKnowledge> hits = keywordSearchMeili(query, terms, limit);
            if (!hits.isEmpty()) return hits;
            // 索引空/未重建时不静默返回空，回退 MySQL 保证召回（首次切换引擎未 reindex 的常见场景）
            log.debug("[Keyword] Meilisearch 无命中，回退 MySQL 关键词召回");
        }
        return keywordSearchMysql(terms, limit);
    }

    /**
     * Meilisearch 关键词召回：索引取 id + 相关度 → 批量载回实体（@TableLogic 自动过滤逻辑删除）
     * → 剔除非生效文档的块（docId 为空的手动知识块保留，与 MySQL 路 isNull(doc_id) 语义一致）
     * → 保持索引给出的相关度顺序，截到 limit
     */
    private List<AiKnowledge> keywordSearchMeili(String query, List<String> terms, int limit) {
        // 过量取回（×2）为状态过滤留余量，避免被弃用/解析中文档的块挤掉有效命中
        List<KeywordIndexService.ScoredId> scored = keywordIndexService.search(query, Math.max(limit * 2, limit));
        if (scored.isEmpty()) return List.of();
        Map<String, Double> scoreById = new LinkedHashMap<>();
        for (KeywordIndexService.ScoredId s : scored) scoreById.put(s.id(), s.score());
        List<AiKnowledge> loaded;
        try {
            loaded = knowledgeMapper.selectBatchIds(scoreById.keySet());
        } catch (Exception e) {
            log.warn("[Keyword] 批量载回知识块失败，降级 MySQL: {}", e.getMessage());
            return List.of();
        }
        if (loaded.isEmpty()) return List.of();
        Map<String, AiKnowledge> byId = loaded.stream()
                .collect(Collectors.toMap(k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
        Set<String> blockedDocIds = loadNonRetrievableDocIds(byId);

        List<AiKnowledge> result = new ArrayList<>();
        for (Map.Entry<String, Double> e : scoreById.entrySet()) {
            if (result.size() >= limit) break;
            AiKnowledge k = byId.get(e.getKey());
            if (k == null) continue; // 索引有、库已删（漂移）：跳过，reindex 可修正
            String docId = k.getDocId() == null ? null : String.valueOf(k.getDocId());
            if (docId != null && !docId.isBlank() && blockedDocIds.contains(docId)) continue;
            k.setKwScore(e.getValue());   // Meilisearch _rankingScore 已是 0~1 绝对分，直接进融合
            fillTermStats(k, terms);
            result.add(k);
        }
        return result;
    }

    /** 回填词元命中统计（titleHit 参与融合的标题奖励；hitTerms/totalTerms 供检索调试展示） */
    private void fillTermStats(AiKnowledge k, List<String> terms) {
        int hit = 0;
        for (String term : terms) {
            if (countOccurrences(k.getContent(), term) > 0 || countOccurrences(k.getTitle(), term) > 0) hit++;
        }
        k.setHitTerms(hit);
        k.setTotalTerms(terms.size());
        k.setTitleHit(k.getTitle() != null && terms.stream().anyMatch(k.getTitle()::contains));
    }

    /**
     * MySQL 关键词召回（降级路径）：词元 OR LIKE（content/title），自动排除非生效文档；
     * 命中后按词频加权（tf×idf，标题词频×2）在命中集内归一化到 kwScore（0~1）。
     * 注意：LIKE 无法走索引，知识块量大时依赖 keywordTimeoutMs 超时兜底。
     */
    private List<AiKnowledge> keywordSearchMysql(List<String> terms, int limit) {
        Future<List<AiKnowledge>> future;
        try {
            future = keywordFallbackPool.submit(() -> {
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
                wrapper.last("LIMIT " + limit);
                List<AiKnowledge> hits = knowledgeMapper.selectList(wrapper);
                if (hits.isEmpty()) return hits;
                return scoreKeywordHits(hits, terms);
            });
        } catch (RejectedExecutionException e) {
            // 队列已满（LIKE 查询积压）或已停机：跳过关键词路，向量路结果照常返回
            log.warn("关键词降级检索繁忙，本次跳过关键词召回");
            return List.of();
        }
        try {
            return future.get(keywordTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 取消：未开始的任务直接出队，避免超时后仍堆积无人取用的慢 LIKE 查询
            future.cancel(true);
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
        if (chunkIndex == 0) return configService.getDouble("retrieval.positionBonus", 0.03);
        if (chunkIndex <= 2) return configService.getDouble("retrieval.sectionBonus", 0.01);
        return 0;
    }

    private double parseScore(Double score) {
        return score == null ? 0 : score;
    }

    private Hit buildHit(Document doc, AiKnowledge k, String kid, double score) {
        Map<String, Object> md = doc.getMetadata();
        String docId = metadataDocId(doc);
        String title = md.get("title") == null ? "" : String.valueOf(md.get("title"));
        List<String> images = imagesFromMd(md);
        Integer chunkIndex = null;
        String titlePath = null;
        // M6 RedisVectorStore 可能丢弃 metadata（docId/title/images 均可能为空），用批量加载的知识块兜底
        if (k != null) {
            if (docId == null || docId.isEmpty()) docId = String.valueOf(k.getDocId());
            if (title.isEmpty()) title = k.getTitle();
            if (chunkIndex == null) chunkIndex = k.getChunkIndex();
            if (titlePath == null || titlePath.isBlank()) titlePath = k.getTitlePath();
            if (images.isEmpty() && k.getImages() != null && !k.getImages().isBlank()) {
                images = com.alibaba.fastjson2.JSON.parseArray(k.getImages(), String.class);
            }
        }
        if (titlePath == null) {
            Object tp = md.get("titlePath");
            titlePath = tp == null ? null : String.valueOf(tp);
        }
        if (docId == null) docId = "";
        return new Hit(kid, docId, title, doc.getText(), images, score, chunkIndex, titlePath);
    }

    /**
     * 批量加载向量命中的知识块（一次 selectBatchIds，替代逐条 selectById；失败返回空 Map 走原降级）
     */
    private Map<String, AiKnowledge> loadKnowledgeBatch(List<Document> vectorDocs) {
        if (vectorDocs == null || vectorDocs.isEmpty()) return Map.of();
        List<String> ids = vectorDocs.stream()
                .map(d -> String.valueOf(d.getId()))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        try {
            return knowledgeMapper.selectBatchIds(ids).stream()
                    .collect(Collectors.toMap(k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
        } catch (Exception e) {
            log.warn("批量加载知识块元数据失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 不可召回文档 id 集合（status<>0：弃用 1 / 解析中 2 / 解析失败 3），用于向量路过滤；
     * 关键词路已按 status=0 过滤，此处保证两条召回路径语义一致
     * （尤其：重解析 diff 复用保留旧向量、崩溃残留半成品，其向量不应进入上下文）
     */
    private Set<String> loadNonRetrievableDocIds(Map<String, AiKnowledge> kidMap) {
        Set<String> docIds = kidMap.values().stream()
                .map(AiKnowledge::getDocId)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toSet());
        if (docIds.isEmpty()) return Set.of();
        try {
            List<AiDocument> blocked = documentMapper.selectList(
                    new QueryWrapper<AiDocument>()
                            .select("id")
                            .in("id", docIds)
                            .ne("status", 0)
                            .eq("deleted", 0));
            return blocked.stream().map(d -> String.valueOf(d.getId())).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("查询非生效文档失败: {}", e.getMessage());
            return Set.of();
        }
    }

    private String metadataDocId(Document doc) {
        Object v = doc.getMetadata().get("docId");
        return v == null ? null : String.valueOf(v);
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
                k.getTitle(), k.getContent(), images, score, k.getChunkIndex(), k.getTitlePath());
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

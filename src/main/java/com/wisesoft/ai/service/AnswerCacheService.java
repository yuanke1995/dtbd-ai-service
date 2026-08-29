package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.AiAnswerCacheMapper;
import com.wisesoft.ai.model.AiAnswerCache;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 相似问题答案缓存（语义缓存）
 * <p>
 * - 问题 embedding 与知识块同模型；内存中与历史问题向量做余弦相似度，
 *   达到阈值（semanticCache.threshold，默认 0.96）即命中，跳过检索+LLM 直接返回历史答案
 * - 存储走 MySQL（c_ai_answer_cache），启动全量加载进内存（条数上限 semanticCache.maxEntries 默认 500，LRU 按时间淘汰）
 * - 知识库变更时整体失效（DocumentService 在解析成功/删除/回滚/启停用/补块处调 clearAll），
 *   宁可少缓存不可答错——缓存答案对应的只是"当时"的知识库快照
 * - embedding 调用失败：查询侧静默跳过（放行走正常流程），写入侧丢弃（不阻断回答）
 *
 * @author yuanke
 */
@Slf4j
@Service
public class AnswerCacheService {

    private final AiAnswerCacheMapper cacheMapper;
    private final EmbeddingModel embeddingModel;
    private final ConfigService configService;

    /** 内存索引：全量缓存条目（volatile 整体替换，读多写少） */
    private volatile List<Entry> index = List.of();

    public AnswerCacheService(AiAnswerCacheMapper cacheMapper, EmbeddingModel embeddingModel, ConfigService configService) {
        this.cacheMapper = cacheMapper;
        this.embeddingModel = embeddingModel;
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从 DB 全量加载进内存（启动/清空后重建） */
    public void reload() {
        try {
            List<AiAnswerCache> all = cacheMapper.selectList(new LambdaQueryWrapper<AiAnswerCache>()
                    .orderByAsc(AiAnswerCache::getCreateTime));
            List<Entry> list = new ArrayList<>(all.size());
            for (AiAnswerCache c : all) {
                try {
                    list.add(new Entry(c.getId(), c.getQuestion(), parseVector(c.getEmbedding()), c));
                } catch (Exception ignored) {
                    // 单条向量解析失败跳过
                }
            }
            index = list;
            log.info("[ANSWER-CACHE] 加载 {} 条相似问题缓存", list.size());
        } catch (Exception e) {
            log.warn("[ANSWER-CACHE] 缓存加载失败（查询将全部放行）: {}", e.getMessage());
        }
    }

    private boolean enabled() {
        return configService.getBoolean("semanticCache.enabled");
    }

    private double threshold() {
        return configService.getDouble("semanticCache.threshold", 0.96);
    }

    private int maxEntries() {
        return configService.getInt("semanticCache.maxEntries", 500);
    }

    /**
     * 查询相似问题命中。返回命中的缓存实体（含问题/答案/来源等），未命中或未启用返回 null。
     * 命中时异步累加命中计数。
     */
    public AiAnswerCache lookup(String question) {
        if (!enabled() || question == null || question.isBlank()) return null;
        if (index.isEmpty()) return null;
        float[] qv;
        try {
            qv = embeddingModel.embed(question);
        } catch (Exception e) {
            log.warn("[ANSWER-CACHE] 查询向量化失败，放行: {}", e.getMessage());
            return null;
        }
        double thr = threshold();
        Entry best = null;
        double bestScore = 0;
        for (Entry e : index) {
            double s = cosine(qv, e.vector);
            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }
        if (best == null || bestScore < thr) {
            log.debug("[ANSWER-CACHE] 未命中 best={} thr={}", String.format("%.4f", bestScore), thr);
            return null;
        }
        AiAnswerCache hit = best.cache;
        log.info("[ANSWER-CACHE] 命中 similarity={} question=[{}] → cache=[{}]", String.format("%.4f", bestScore), question, best.question);
        ThreadPoolManager.execute(() -> {
            try {
                AiAnswerCache upd = new AiAnswerCache();
                upd.setId(hit.getId());
                upd.setHitCount((hit.getHitCount() == null ? 0 : hit.getHitCount()) + 1);
                cacheMapper.updateById(upd);
            } catch (Exception ignored) {
            }
        });
        return hit;
    }

    /**
     * 异步写入缓存（回答完成后调用；启用判断/失败静默，不阻断回答）
     *
     * @param sources 引用来源 JSON（done payload 的 sources，可为 null）
     * @param images  关联图片 URL 列表（可为 null）
     * @param related 相关追问列表（可为 null）
     */
    public void storeAsync(String question, String answer, String sources,
                           List<String> images, List<String> related, String messageId) {
        if (!enabled() || question == null || question.isBlank() || answer == null || answer.isBlank()) return;
        ThreadPoolManager.execute(() -> {
            try {
                float[] v = embeddingModel.embed(question);
                AiAnswerCache c = new AiAnswerCache();
                c.setQuestion(question);
                c.setEmbedding(JSON.toJSONString(v));
                c.setAnswer(answer);
                c.setSources(sources);
                c.setImages((images == null || images.isEmpty()) ? null : JSON.toJSONString(images));
                c.setRelated((related == null || related.isEmpty()) ? null : JSON.toJSONString(related));
                c.setMessageId(messageId);
                c.setHitCount(0);
                cacheMapper.insert(c);
                // 内存同步追加；超上限淘汰最早条目（DB+内存一起删）
                List<Entry> cur = new ArrayList<>(index);
                cur.add(new Entry(c.getId(), question, v, c));
                int max = Math.max(10, maxEntries());
                while (cur.size() > max) {
                    Entry oldest = cur.remove(0);
                    cacheMapper.deleteById(oldest.id);
                }
                index = cur;
                log.info("[ANSWER-CACHE] 已缓存问题: {}（共 {} 条）", question, cur.size());
            } catch (Exception e) {
                log.warn("[ANSWER-CACHE] 缓存写入失败（不影响回答）: {}", e.getMessage());
            }
        });
    }

    /** 整体失效清空（知识库变更时调用：解析成功/删除/回滚/启停用/手动补块） */
    public void clearAll() {
        try {
            cacheMapper.delete(new LambdaQueryWrapper<>());
            index = List.of();
            log.info("[ANSWER-CACHE] 知识库变更，答案缓存已整体清空");
        } catch (Exception e) {
            log.warn("[ANSWER-CACHE] 缓存清空失败: {}", e.getMessage());
        }
    }

    /** 统计信息（设置页展示） */
    public Map<String, Object> stats() {
        return Map.of(
                "enabled", enabled(),
                "threshold", threshold(),
                "maxEntries", maxEntries(),
                "count", index.size());
    }

    private float[] parseVector(String json) {
        List<Float> list = JSON.parseArray(json, Float.class);
        float[] v = new float[list.size()];
        for (int i = 0; i < list.size(); i++) v[i] = list.get(i);
        return v;
    }

    /** 余弦相似度（embedding 服务返回归一化向量时退化为点积，此处按通用公式实现） */
    private static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 内存索引条目 */
    private record Entry(String id, String question, float[] vector, AiAnswerCache cache) {
    }
}
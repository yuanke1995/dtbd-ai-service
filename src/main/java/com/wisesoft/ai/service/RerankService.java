package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重排服务：调用独立 reranker 服务（OpenAI 兼容 POST /v1/rerank，如 scripts/rerank_server.py 本地服务）。
 *
 * <p>注意：Ollama 官方（含 1.x）没有 /api/rerank 端点、官方库也没有 rerank 模型，
 * 社区模型仅能经 /api/embed 近似且部分环境 embedding 被禁用，因此本项目只支持 OpenAI 兼容协议。
 * 本地部署参考 scripts/win|mac/start_rerank_server.*（sentence-transformers CrossEncoder 服务）。
 *
 * <p>配置从 c_ai_config 动态读取（键 rerank.*：enabled/baseUrl/model/timeoutMillis，设置页保存即生效）。
 * yml 中的 ai-app.retrieval.rerank 仅作首次兜底默认值。
 * 未启用/探测失败/调用失败时静默回退为输入顺序（混合检索已按融合分排序）。
 * 探测/失败结果缓存，首次失败记忆禁用（进程内不再重试）；配置变更（baseUrl 等）自动重建客户端并重置探测。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RerankService {

    private final ConfigService configService;
    private final AtomicBoolean supportChecked = new AtomicBoolean(false);
    private volatile boolean rerankSupported = false;
    private volatile RestClient client;          // 按当前 baseUrl/timeout 懒构建
    private volatile String clientBaseUrl = "";  // 已构建 client 对应的 baseUrl（变化时重建）
    private volatile int clientTimeout = 0;      // 已构建 client 对应的 timeout
    /** 最近一次失败时间戳（失败冷却：短暂故障后自动恢复，避免永久禁用） */
    private volatile long lastFailTs = 0L;
    private static final long FAIL_COOLDOWN_MS = 60_000L;  // 失败后冷却 60s 再重新探测

    public RerankService(ConfigService configService) {
        this.configService = configService;
        log.info("[Rerank] 配置从 c_ai_config 动态读取（rerank.*，OpenAI 兼容 /v1/rerank），保存即生效");
    }

    /** 动态读配置（保存即生效） */
    private boolean enabled() { return configService.getBoolean("rerank.enabled"); }

    private String model() {
        String m = configService.get("rerank.model");
        return m == null || m.isBlank() ? "BAAI/bge-reranker-v2-m3" : m;
    }

    private int timeoutMillis() {
        int t = configService.getInt("rerank.timeoutMillis");
        return t > 0 ? t : 5000;
    }

    private String baseUrl() {
        String b = configService.get("rerank.baseUrl");
        return b == null || b.isBlank() ? "http://localhost:7997" : b;
    }

    /** 配置变化（baseUrl/timeout）时重建 RestClient 并重置探测缓存 */
    private RestClient client() {
        String url = baseUrl();
        int t = timeoutMillis();
        if (client == null || !url.equals(clientBaseUrl) || t != clientTimeout) {
            synchronized (this) {
                if (client == null || !url.equals(clientBaseUrl) || t != clientTimeout) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(2000);
                    factory.setReadTimeout(t);
                    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
                    clientBaseUrl = url;
                    clientTimeout = t;
                    // 配置变更后重新探测
                    supportChecked.set(false);
                    rerankSupported = false;
                    log.info("[Rerank] 客户端重建: {}，模型 {}，超时 {}ms", url, model(), t);
                }
            }
        }
        return client;
    }

    /**
     * 重排候选（传入按融合分排序的候选，返回重排后的顺序）；未启用/不支持/失败时原样返回
     */
    public List<HybridRetrievalService.Hit> rank(List<HybridRetrievalService.Hit> candidates, String query) {
        if (!enabled() || candidates == null || candidates.size() < 2) return candidates;
        if (!checkSupport()) return candidates;

        try {
            List<String> docs = candidates.stream()
                    .map(h -> h.title() + "\n" + h.content())
                    .toList();
            List<Double> scores = rankByOpenAiRerank(query, docs);
            if (scores == null || scores.size() != docs.size()) return candidates;

            List<HybridRetrievalService.Hit> ranked = new ArrayList<>(candidates);
            ranked.sort((a, b) -> Double.compare(
                    scores.get(candidates.indexOf(b)), scores.get(candidates.indexOf(a))));
            log.info("[Rerank] 候选 {} 条重排完成", candidates.size());
            return ranked;
        } catch (Exception e) {
            // 失败记录：冷却期内不重试（避免每个请求都撞一次），冷却结束后自动恢复探测
            rerankSupported = false;
            lastFailTs = System.currentTimeMillis();
            log.warn("[Rerank] 服务调用失败，回退融合分排序（{}s 后自动重试）: {}", FAIL_COOLDOWN_MS / 1000, e.getMessage());
            return candidates;
        }
    }

    /** OpenAI 兼容 /v1/rerank：真交叉编码重排，直接返回 relevance_score */
    private List<Double> rankByOpenAiRerank(String query, List<String> docs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model());
        body.put("query", query);
        body.put("documents", docs);
        body.put("top_n", docs.size());

        String resp = client().post()
                .uri("/v1/rerank")
                .body(body)
                .retrieve()
                .body(String.class);
        if (resp == null) return null;
        JSONObject root = JSON.parseObject(resp);
        JSONArray results = root.getJSONArray("results");
        if (results == null || results.isEmpty()) return null;

        Double[] scores = new Double[docs.size()];
        for (int i = 0; i < results.size(); i++) {
            var item = results.getJSONObject(i);
            int idx = item.getIntValue("index");
            if (idx >= 0 && idx < scores.length) {
                scores[idx] = item.getDoubleValue("relevance_score");
            }
        }
        List<Double> list = new ArrayList<>(docs.size());
        for (Double s : scores) list.add(s == null ? 0.0 : s);
        return list;
    }

    /**
     * 强制探测服务可用性（绕过缓存，每次真实请求 /v1/models；供设置页"开启前校验"调用）
     * 服务修复后调用本方法可立即恢复，不受此前失败记忆影响
     */
    public boolean checkAvailable() {
        supportChecked.set(false);
        rerankSupported = false;
        boolean ok = checkSupport();
        log.info("[Rerank] 强制探测结果: {}", ok);
        return ok;
    }

    /**
     * 服务可用性探测（仅探测一次并缓存；配置变更后 client() 会自动重置）：
     * GET /v1/models 确认 OpenAI 兼容服务在线
     */
    private boolean checkSupport() {
        // 已探测过：成功直接返回；失败且冷却未过 → 仍不可用；冷却已过 → 重新探测（瞬时故障自动恢复）
        if (supportChecked.get()) {
            if (rerankSupported) return true;
            if (System.currentTimeMillis() - lastFailTs < FAIL_COOLDOWN_MS) return false;
            supportChecked.set(false);  // 冷却结束，允许重新探测
        }
        synchronized (this) {
            if (supportChecked.get()) return rerankSupported;
            try {
                String resp = client().get().uri("/v1/models").retrieve().body(String.class);
                rerankSupported = resp != null && resp.contains("data");
                if (!rerankSupported) {
                    lastFailTs = System.currentTimeMillis();
                    log.warn("[Rerank] /v1/models 无有效响应，重排不可用（回退融合分排序）");
                }
            } catch (Exception e) {
                rerankSupported = false;
                lastFailTs = System.currentTimeMillis();
                log.warn("[Rerank] 服务探测失败（回退融合分排序）baseUrl={}: {}", baseUrl(), e.getMessage());
            }
            supportChecked.set(true);
            return rerankSupported;
        }
    }
}

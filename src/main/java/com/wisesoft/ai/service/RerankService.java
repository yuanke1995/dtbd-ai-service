package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 重排服务
 * 优先使用 Ollama /api/rerank（需 Ollama >= 1.0 且已拉取 rerank 模型，如 bge-reranker-v2-m3）；
 * 版本不支持或调用失败时静默回退为输入顺序（混合检索已按融合分排序）。
 * 探测结果缓存，避免每次请求都探测。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RerankService {

    private static final String RERANK_MODEL = "bge-reranker-v2-m3";

    private final RestClient ollamaClient;
    private final AtomicBoolean supportChecked = new AtomicBoolean(false);
    private volatile boolean rerankSupported = false;

    public RerankService(AiAppProperties properties) {
        // 从 vision base-url 推导 Ollama 地址（同机本地 Ollama）
        String baseUrl = properties.getVision().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        baseUrl = baseUrl.replace("/v1", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.ollamaClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * 重排候选（传入按融合分排序的候选，返回重排后的顺序）；不支持时原样返回
     */
    public List<HybridRetrievalService.Hit> rank(List<HybridRetrievalService.Hit> candidates, String query) {
        if (candidates == null || candidates.size() < 2) return candidates;
        if (!checkSupport()) return candidates;

        try {
            List<String> docs = candidates.stream()
                    .map(h -> h.title() + "\n" + h.content())
                    .toList();
            Map<String, Object> body = new HashMap<>();
            body.put("model", RERANK_MODEL);
            body.put("query", query);
            body.put("documents", docs);
            body.put("return_documents", false);

            String resp = ollamaClient.post()
                    .uri("/api/rerank")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (resp == null) return candidates;

            // 解析 {"results":[{"index":0,"relevance_score":0.9},...]}
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(resp);
            var results = root.getJSONArray("results");
            if (results == null || results.isEmpty()) return candidates;

            Map<Integer, Double> scoreMap = new HashMap<>();
            for (int i = 0; i < results.size(); i++) {
                var item = results.getJSONObject(i);
                scoreMap.put(item.getIntValue("index"), item.getDoubleValue("relevance_score"));
            }
            List<HybridRetrievalService.Hit> ranked = new ArrayList<>(candidates);
            ranked.sort((a, b) -> {
                double sa = scoreMap.getOrDefault(candidates.indexOf(a), 0.0);
                double sb = scoreMap.getOrDefault(candidates.indexOf(b), 0.0);
                return Double.compare(sb, sa);
            });
            log.info("[Rerank] 候选 {} 条重排完成", candidates.size());
            return ranked;
        } catch (Exception e) {
            log.debug("Ollama rerank 不可用，回退规则排序: {}", e.getMessage());
            return candidates;
        }
    }

    private boolean checkSupport() {
        if (supportChecked.get()) return rerankSupported;
        synchronized (this) {
            if (supportChecked.get()) return rerankSupported;
            try {
                String ver = ollamaClient.get().uri("/api/version").retrieve().body(String.class);
                if (ver != null) {
                    var root = com.alibaba.fastjson2.JSON.parseObject(ver);
                    String version = root.getString("version");
                    // rerank API 自 Ollama 1.0 起支持
                    rerankSupported = version != null && isVersionAtLeast(version, 1, 0);
                }
            } catch (Exception e) {
                log.debug("Ollama 探测失败: {}", e.getMessage());
            }
            supportChecked.set(true);
            log.info("[Rerank] Ollama rerank 支持: {}", rerankSupported);
            return rerankSupported;
        }
    }

    private boolean isVersionAtLeast(String version, int major, int minor) {
        try {
            String[] parts = version.split("\\.")[0].split("-")[0].split("\\.");
            int m = Integer.parseInt(parts[0]);
            if (m != major) return m > major;
            if (parts.length < 2) return true;
            return Integer.parseInt(parts[1]) >= minor;
        } catch (Exception e) {
            return false;
        }
    }
}

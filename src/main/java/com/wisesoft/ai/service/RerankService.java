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
 * 重排服务。Ollama 官方（含 1.x）没有 /api/rerank 端点、官方库也没有 rerank 模型，
 * 社区模型（qllama/bge-reranker-v2-m3 等）在 Ollama 中仅作为 embedding 模型运行，
 * 因此本服务支持两种协议：
 * <ul>
 *   <li>provider=ollama（默认）：调 Ollama /api/embed 对 query+各候选批量嵌入，余弦相似度近似重排
 *       （非真 cross-encoder，质量打折但零额外服务；部署：ollama pull qllama/bge-reranker-v2-m3）</li>
 *   <li>provider=openai：调独立服务 OpenAI 兼容 POST /v1/rerank（Infinity/TEI 网关，真交叉编码重排）</li>
 * </ul>
 * 配置从 c_ai_config 动态读取（键 rerank.*，设置页保存即生效，无需重启）：
 * provider / enabled / baseUrl / model / timeoutMillis。yml 中的 ai-app.retrieval.rerank 仅作首次兜底默认值。
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

    public RerankService(ConfigService configService) {
        this.configService = configService;
        log.info("[Rerank] 配置从 c_ai_config 动态读取（rerank.*），保存即生效");
    }

    /** 动态读配置（保存即生效） */
    private boolean enabled() { return configService.getBoolean("rerank.enabled"); }

    private String provider() {
        String p = configService.get("rerank.provider");
        return p == null || p.isBlank() ? "ollama" : p;
    }

    private String model() {
        String m = configService.get("rerank.model");
        return m == null || m.isBlank()
                ? ("ollama".equals(provider()) ? "qllama/bge-reranker-v2-m3" : "BAAI/bge-reranker-v2-m3")
                : m;
    }

    private int timeoutMillis() {
        int t = configService.getInt("rerank.timeoutMillis");
        return t > 0 ? t : 5000;
    }

    private String baseUrl() {
        String b = configService.get("rerank.baseUrl");
        if (b == null || b.isBlank()) {
            return "ollama".equals(provider()) ? "http://localhost:11434" : "http://localhost:7997";
        }
        return b;
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
                    log.info("[Rerank] 客户端重建: {}，模型 {}，超时 {}ms，provider={}", url, model(), t, provider());
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
            List<Double> scores = "ollama".equals(provider())
                    ? rankByOllamaEmbed(query, docs)
                    : rankByOpenAiRerank(query, docs);
            if (scores == null || scores.size() != docs.size()) return candidates;

            List<HybridRetrievalService.Hit> ranked = new ArrayList<>(candidates);
            ranked.sort((a, b) -> Double.compare(
                    scores.get(candidates.indexOf(b)), scores.get(candidates.indexOf(a))));
            log.info("[Rerank] 候选 {} 条重排完成 (provider={})", candidates.size(), provider());
            return ranked;
        } catch (Exception e) {
            // 失败记忆：标记不可用，避免每个请求都重试
            rerankSupported = false;
            log.warn("[Rerank] 服务调用失败，回退融合分排序（进程内已记忆禁用）: {}", e.getMessage());
            return candidates;
        }
    }

    /** Ollama /api/embed 近似重排：query 与各 doc 批量嵌入，返回余弦相似度（越大越相关） */
    private List<Double> rankByOllamaEmbed(String query, List<String> docs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model());
        List<String> inputs = new ArrayList<>();
        inputs.add(query);
        inputs.addAll(docs);
        body.put("input", inputs);

        String resp = client().post()
                .uri("/api/embed")
                .body(body)
                .retrieve()
                .body(String.class);
        if (resp == null) return null;
        JSONObject root = JSON.parseObject(resp);
        JSONArray embeds = root.getJSONArray("embeddings");
        if (embeds == null || embeds.size() < 2) return null;

        double[] q = toArray(embeds.getJSONArray(0));
        List<Double> scores = new ArrayList<>(docs.size());
        for (int i = 1; i < embeds.size(); i++) {
            scores.add(cosine(q, toArray(embeds.getJSONArray(i))));
        }
        return scores;
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

    private double[] toArray(JSONArray arr) {
        double[] out = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.getDoubleValue(i);
        return out;
    }

    private double cosine(double[] a, double[] b) {
        if (a.length == 0 || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 服务可用性探测（仅探测一次并缓存；配置变更后 client() 会自动重置）：
     * ollama → GET /api/tags 确认服务在线且模型已拉取；
     * openai → GET /v1/models 确认 OpenAI 兼容服务在线
     */
    private boolean checkSupport() {
        if (supportChecked.get()) return rerankSupported;
        synchronized (this) {
            if (supportChecked.get()) return rerankSupported;
            try {
                if ("ollama".equals(provider())) {
                    String resp = client().get().uri("/api/tags").retrieve().body(String.class);
                    rerankSupported = resp != null && resp.contains(model().split(":")[0]);
                    if (!rerankSupported) {
                        log.warn("[Rerank] Ollama 在线但未找到模型 {}（请执行: ollama pull {}）", model(), model());
                    }
                } else {
                    String resp = client().get().uri("/v1/models").retrieve().body(String.class);
                    rerankSupported = resp != null && resp.contains("data");
                }
            } catch (Exception e) {
                rerankSupported = false;
                log.warn("[Rerank] 服务探测失败（回退融合分排序）: {}", e.getMessage());
            }
            supportChecked.set(true);
            return rerankSupported;
        }
    }
}

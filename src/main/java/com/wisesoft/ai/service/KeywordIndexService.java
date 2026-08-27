package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiKnowledge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 关键词索引服务（Meilisearch，裸 HTTP 调用，与 RerankService/VisionService 风格一致）
 * <p>
 * 用途：替代 MySQL 的 content/title LIKE 全表扫描，提供中文分词 + 相关度打分的关键词召回。
 * 索引文档 = 知识块（primaryKey=知识块 id），仅存检索必需字段，正文内容仍以 MySQL 为准。
 * <p>
 * 可靠性策略（与重排服务一致）：
 * - 启用开关 keyword.engine=meilisearch；未启用/探测失败/调用失败 → 调用方自动降级回 MySQL LIKE
 * - 探测结果缓存 + 失败冷却（keyword.failCooldownMs），冷却结束自动重探，避免每请求都撞
 * - 配置（baseUrl/timeout）变更自动重建客户端并重置探测
 * - 写索引全部 best-effort（失败只告警不抛），最终一致由 reindexAll() 全量重建兜底
 *   （切换引擎保存配置时自动触发，无需手动调接口；运维也可调 /api/ai/search-index/reindex）
 */
@Slf4j
@Service
public class KeywordIndexService {

    private final AiAppProperties properties;
    private final ConfigService configService;
    private final AiKnowledgeMapper knowledgeMapper;

    private final AtomicBoolean supportChecked = new AtomicBoolean(false);
    private volatile boolean available = false;
    private volatile long lastFailTs = 0L;
    /** 索引与 settings 是否已初始化（每次客户端重建后重新初始化） */
    private volatile boolean indexReady = false;

    /** 全量重建进行中标志（防并发重复重建） */
    private final AtomicBoolean reindexing = new AtomicBoolean(false);

    private volatile RestClient client;
    private volatile String clientBaseUrl = "";
    private volatile int clientTimeout = 0;

    public KeywordIndexService(AiAppProperties properties, ConfigService configService, AiKnowledgeMapper knowledgeMapper) {
        this.properties = properties;
        this.configService = configService;
        this.knowledgeMapper = knowledgeMapper;
    }

    // ==================== 配置（动态读取，保存即生效） ====================

    /** 当前关键词引擎（mysql / meilisearch） */
    public String engine() {
        String e = configService.get("keyword.engine");
        return e == null || e.isBlank() ? properties.getKeyword().getEngine() : e.trim();
    }

    /** 是否启用 Meilisearch 引擎（仅看配置，不含可用性） */
    public boolean enabled() {
        return "meilisearch".equalsIgnoreCase(engine());
    }

    private String baseUrl() {
        String b = configService.get("keyword.baseUrl");
        return b == null || b.isBlank() ? properties.getKeyword().getBaseUrl() : b.trim();
    }

    private int timeoutMillis() {
        int t = configService.getInt("keyword.timeoutMillis", properties.getKeyword().getTimeoutMillis());
        return t > 0 ? t : 1000;
    }

    private long failCooldownMs() {
        return configService.getInt("keyword.failCooldownMs", 60_000);
    }

    /** master key 只从 env/yml 读取，不入 c_ai_config（避免密钥明文落库） */
    private String apiKey() {
        return properties.getKeyword().getApiKey();
    }

    private String index() {
        return properties.getKeyword().getIndex();
    }

    /** 配置变化（baseUrl/timeout）时重建 RestClient 并重置探测与索引初始化状态 */
    private RestClient client() {
        String url = baseUrl();
        int t = timeoutMillis();
        if (client == null || !url.equals(clientBaseUrl) || t != clientTimeout) {
            synchronized (this) {
                if (client == null || !url.equals(clientBaseUrl) || t != clientTimeout) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(2000);
                    factory.setReadTimeout(t);
                    RestClient.Builder builder = RestClient.builder().baseUrl(url).requestFactory(factory);
                    String key = apiKey();
                    if (key != null && !key.isBlank()) {
                        builder.defaultHeader("Authorization", "Bearer " + key);
                    }
                    client = builder.build();
                    clientBaseUrl = url;
                    clientTimeout = t;
                    supportChecked.set(false);
                    available = false;
                    indexReady = false;
                    log.info("[Keyword] Meilisearch 客户端重建: {}，索引 {}，超时 {}ms", url, index(), t);
                }
            }
        }
        return client;
    }

    // ==================== 可用性探测 ====================

    /**
     * 引擎是否可用于本次检索：未启用直接 false；探测结果缓存，失败冷却期内不重试
     */
    public boolean isAvailable() {
        if (!enabled()) return false;
        return checkSupport();
    }

    private boolean checkSupport() {
        if (supportChecked.get()) {
            if (available) return true;
            if (System.currentTimeMillis() - lastFailTs < failCooldownMs()) return false;
            supportChecked.set(false); // 冷却结束，允许重新探测
        }
        synchronized (this) {
            if (supportChecked.get()) return available;
            try {
                String resp = client().get().uri("/health").retrieve().body(String.class);
                available = resp != null && resp.contains("available");
                if (!available) {
                    lastFailTs = System.currentTimeMillis();
                    log.warn("[Keyword] /health 无有效响应，Meilisearch 不可用（关键词路降级 MySQL）");
                }
            } catch (Exception e) {
                available = false;
                lastFailTs = System.currentTimeMillis();
                log.warn("[Keyword] Meilisearch 探测失败（关键词路降级 MySQL）baseUrl={}: {}", baseUrl(), e.getMessage());
            }
            supportChecked.set(true);
            return available;
        }
    }

    /** 强制探测（绕过缓存，供设置页切换引擎前校验） */
    public boolean checkAvailable() {
        supportChecked.set(false);
        available = false;
        boolean ok = checkSupport();
        log.info("[Keyword] 强制探测结果: {}", ok);
        return ok;
    }

    /** 不可用原因（可用时返回 null），供检索调试与运维接口展示 */
    public String debugUnavailableReason() {
        if (!enabled()) return "未启用（keyword.engine=" + engine() + "）";
        if (supportChecked.get() && !available) {
            long left = failCooldownMs() - (System.currentTimeMillis() - lastFailTs);
            return left > 0
                    ? "服务不可用，冷却中（" + (left / 1000) + "s 后重试）"
                    : "服务探测失败：" + baseUrl();
        }
        return null;
    }

    /** 惰性创建索引并设置可搜索/可过滤字段（幂等；失败仅告警） */
    private void ensureIndex() {
        if (indexReady) return;
        synchronized (this) {
            if (indexReady) return;
            try {
                // 创建索引（已存在时 Meilisearch 返回 index_already_exists，忽略）
                try {
                    client().post().uri("/indexes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("uid", index(), "primaryKey", "id"))
                            .retrieve().body(String.class);
                } catch (Exception ignored) {
                    // 已存在或并发创建，交由下面的 settings 校准
                }
                // 可搜索字段按优先级排序（title > titlePath > content），docId 可过滤（按文档删除用）
                client().patch().uri("/indexes/" + index() + "/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "searchableAttributes", List.of("title", "titlePath", "content"),
                                "filterableAttributes", List.of("docId")))
                        .retrieve().body(String.class);
                indexReady = true;
                log.info("[Keyword] 索引 {} 初始化完成（searchable: title/titlePath/content, filterable: docId）", index());
            } catch (Exception e) {
                log.warn("[Keyword] 索引初始化失败: {}", e.getMessage());
            }
        }
    }

    // ==================== 检索 ====================

    /** 关键词召回结果：知识块 id + 相关度分（Meilisearch _rankingScore，0~1） */
    public record ScoredId(String id, double score) {
    }

    /**
     * 关键词召回：返回按相关度降序的知识块 id + 分数；失败返回空列表（调用方降级）
     */
    public List<ScoredId> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        if (!isAvailable()) return List.of();
        ensureIndex();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("limit", limit);
            body.put("showRankingScore", true);
            body.put("attributesToRetrieve", List.of("id"));
            String resp = client().post().uri("/indexes/" + index() + "/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class);
            if (resp == null) return List.of();
            JSONArray hits = JSON.parseObject(resp).getJSONArray("hits");
            if (hits == null || hits.isEmpty()) return List.of();
            List<ScoredId> out = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                JSONObject h = hits.getJSONObject(i);
                String id = h.getString("id");
                if (id == null || id.isBlank()) continue;
                Double s = h.getDouble("_rankingScore");
                out.add(new ScoredId(id, s == null ? 0 : s));
            }
            return out;
        } catch (Exception e) {
            markFailed("检索", e);
            return List.of();
        }
    }

    // ==================== 索引写入（best-effort，失败只告警） ====================

    /** upsert 知识块（按 primaryKey=id 覆盖） */
    public void indexChunks(List<AiKnowledge> blocks) {
        if (!enabled() || blocks == null || blocks.isEmpty()) return;
        if (!isAvailable()) return;
        ensureIndex();
        try {
            List<Map<String, Object>> docs = new ArrayList<>(blocks.size());
            for (AiKnowledge k : blocks) {
                if (k == null || k.getId() == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", k.getId());
                d.put("docId", k.getDocId() == null ? "" : k.getDocId());
                d.put("title", k.getTitle() == null ? "" : k.getTitle());
                d.put("titlePath", k.getTitlePath() == null ? "" : k.getTitlePath());
                d.put("content", k.getContent() == null ? "" : k.getContent());
                docs.add(d);
            }
            if (docs.isEmpty()) return;
            client().post().uri("/indexes/" + index() + "/documents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(docs)
                    .retrieve().body(String.class);
            log.debug("[Keyword] 索引写入 {} 块", docs.size());
        } catch (Exception e) {
            markFailed("索引写入", e);
        }
    }

    /** 按知识块 id 批量删除 */
    public void deleteChunks(List<String> ids) {
        if (!enabled() || ids == null || ids.isEmpty()) return;
        if (!isAvailable()) return;
        try {
            client().post().uri("/indexes/" + index() + "/documents/delete-batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ids)
                    .retrieve().body(String.class);
            log.debug("[Keyword] 索引删除 {} 块", ids.size());
        } catch (Exception e) {
            markFailed("索引删除", e);
        }
    }

    /** 按文档删除该文档下所有块（filter=docId） */
    public void deleteByDoc(String docId) {
        if (!enabled() || docId == null || docId.isBlank()) return;
        if (!isAvailable()) return;
        ensureIndex();
        try {
            client().post().uri("/indexes/" + index() + "/documents/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", "docId = \"" + docId + "\""))
                    .retrieve().body(String.class);
            log.debug("[Keyword] 索引删除文档 {} 的全部块", docId);
        } catch (Exception e) {
            markFailed("按文档删除索引", e);
        }
    }

    /** 清空索引全部文档（运维：重建前使用） */
    public void clearAll() {
        if (!enabled()) return;
        if (!isAvailable()) return;
        try {
            client().delete().uri("/indexes/" + index() + "/documents").retrieve().body(String.class);
            log.info("[Keyword] 索引已清空");
        } catch (Exception e) {
            markFailed("清空索引", e);
        }
    }

    /**
     * 全量重建索引：分批（每批 1000）从 MySQL 读取 push，避免一次性载入全部块。
     * 首次切换引擎、索引漂移后使用；切换引擎保存配置时自动触发，也可手动调 /reindex。
     * 防并发重复（AtomicBoolean），后台线程执行不阻塞调用方。
     */
    public void reindexAll() {
        if (!enabled()) {
            log.info("[Reindex] 关键词引擎未启用 Meilisearch（keyword.engine=mysql），跳过全量重建");
            return;
        }
        // 先确保索引存在（首次切换/索引被删时 404 index_not_found 会误判为服务不可用；ensureIndex 幂等，失败仅告警）
        ensureIndex();
        if (!isAvailable()) {
            log.warn("[Reindex] Meilisearch 服务不可用，跳过全量重建");
            return;
        }
        if (!reindexing.compareAndSet(false, true)) {
            log.info("[Reindex] 全量重建进行中，跳过本次触发");
            return;
        }
        new Thread(() -> {
            long total = 0;
            try {
                String lastId = "";
                while (true) {
                    // 只灌有效块（status=0 文档的块 + 手动块 docId IS NULL），使"索引集合 = 有效块集合"闭环：
                    // 弃用/失败文档的块不入索引，启动对账（indexedCount vs 有效块数）不会因残留而每次重建
                    List<AiKnowledge> batch = knowledgeMapper.selectList(
                            new LambdaQueryWrapper<AiKnowledge>()
                                    .gt(AiKnowledge::getId, lastId) // UUID 字符串升序游标
                                    .and(w -> w.isNull(AiKnowledge::getDocId)
                                            .or().inSql(AiKnowledge::getDocId,
                                                    "SELECT id FROM c_ai_document WHERE status=0 AND deleted=0"))
                                    .orderByAsc(AiKnowledge::getId)
                                    .last("LIMIT 1000"));
                    if (batch.isEmpty()) break;
                    indexChunks(batch);
                    lastId = batch.get(batch.size() - 1).getId();
                    total += batch.size();
                    log.info("[Reindex] 已重建 {} 块", total);
                    if (batch.size() < 1000) break;
                }
                log.info("[Reindex] 全量重建完成，共 {} 块", total);
            } catch (Exception e) {
                log.error("[Reindex] 全量重建失败（已完成 {} 块）: {}", total, e.getMessage());
            } finally {
                reindexing.set(false);
            }
        }, "search-index-reindex").start();
    }

    /**
     * 启动索引对账（自愈漂移）：engine=meilisearch 且 keyword.reconcileOnStartup 开（默认 true）时，
     * 比对索引文档数 vs MySQL 有效块数，不一致 → 后台全量重建。
     * 放在 ApplicationReadyEvent：晚于 SchemaMigrator/所有 @PostConstruct，配置与表结构就绪。
     * 多副本：reindexAll 幂等 + AtomicBoolean 防重入，重复重建无害（upsert 覆盖）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            if (!enabled()) return;
            if (!configService.getBoolean("keyword.reconcileOnStartup")) return;
            long effective = effectiveCount();
            long indexed = indexedCount();
            if (effective < 0 || indexed < 0) {
                log.info("[Reconcile] 启动对账跳过（计数不可用：effective={}, indexed={}）", effective, indexed);
                return;
            }
            if (indexed != effective) {
                log.warn("[Reconcile] 索引漂移（索引 {} vs 有效块 {}），自动全量重建", indexed, effective);
                reindexAll();
            } else {
                log.info("[Reconcile] 关键词索引一致（{} 块），无需重建", indexed);
            }
        } catch (Exception e) {
            log.warn("[Reconcile] 启动对账失败: {}", e.getMessage());
        }
    }

    /** MySQL 有效知识块数（status=0 文档的块 + 手动块 docId IS NULL；失败返回 -1） */
    private long effectiveCount() {
        try {
            Long n = knowledgeMapper.selectCount(new LambdaQueryWrapper<AiKnowledge>()
                    .and(w -> w.isNull(AiKnowledge::getDocId)
                            .or().inSql(AiKnowledge::getDocId,
                                    "SELECT id FROM c_ai_document WHERE status=0 AND deleted=0")));
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("[Reconcile] 有效块计数失败: {}", e.getMessage());
            return -1;
        }
    }

    /** 索引内文档数（探测失败或未启用返回 -1；索引尚未创建返回 0） */
    public long indexedCount() {
        if (!isAvailable()) return -1;
        try {
            String resp = client().get().uri("/indexes/" + index() + "/stats").retrieve().body(String.class);
            if (resp == null) return -1;
            Long n = JSON.parseObject(resp).getLong("numberOfDocuments");
            return n == null ? -1 : n;
        } catch (Exception e) {
            // 索引尚未创建（404 index_not_found）= 0 文档，不是服务故障：不触发冷却，
            // 由 ensureIndex/reindexAll 自动创建索引后重建
            if (e.getMessage() != null && e.getMessage().contains("index_not_found")) {
                return 0;
            }
            markFailed("查询索引统计", e);
            return -1;
        }
    }

    /** 调用失败：标记不可用并进入冷却（与重排服务一致，避免每请求都撞） */
    private void markFailed(String action, Exception e) {
        available = false;
        supportChecked.set(true);
        lastFailTs = System.currentTimeMillis();
        log.warn("[Keyword] Meilisearch {}失败（{}s 后自动重试）: {}", action, failCooldownMs() / 1000, e.getMessage());
    }
}

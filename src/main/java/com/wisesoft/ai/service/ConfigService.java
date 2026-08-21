package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiConfigMapper;
import com.wisesoft.ai.model.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置服务：DB（c_ai_config）存储 + 内存缓存
 * <p>
 * - 启动时表空则从 yml/env 默认值灌入
 * - 可编辑白名单：chat.model / chat.temperature / vision.model / vision.prompt（保存即生效）
 * - base-url / api-key / embedding.model 只读（变更需改 yml 重启）
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConfigService {

    /** 可编辑白名单 */
    private static final Map<String, String> EDITABLE = Map.ofEntries(
            Map.entry("chat.model", "智能问答模型名"),
            Map.entry("chat.temperature", "回答温度(0~2)"),
            Map.entry("chat.systemPrompt", "AI助手系统提示词（角色与回答风格）"),
            Map.entry("chat.pipelineThreads", "问答流水线线程数(保存即生效)"),
            Map.entry("vision.model", "视觉识别模型名"),
            Map.entry("vision.prompt", "视觉识别提示词"),
            Map.entry("vision.concurrency", "图片描述并发数（保存即生效）"),
            Map.entry("vision.descCacheVersion", "图片描述缓存版本(改动后重解析全量重新描述)"),
            Map.entry("vision.descCacheTtlDays", "图片描述缓存有效期(天,0=不过期)"),
            Map.entry("chunk.maxChunks", "单文档最大知识块数(0=不限制)"),
            Map.entry("chunk.maxImages", "单文档最多提取图片数(0=不限制)"),
            Map.entry("chunk.overlap", "分块重叠字符数(0=关闭)"),
            Map.entry("chunk.structural", "结构感知切分(标题/段落边界+章节路径注入,需重解析)"),
            Map.entry("chunk.structuralRatio", "结构切分边界阈值比例(0~1,达到maxSize×比例优先段落断块)"),
            Map.entry("upload.maxFileSize", "文档上传大小上限(字节,保存即生效)"),
            Map.entry("retrieval.vectorWeight", "混合检索：向量权重(0~1)"),
            Map.entry("retrieval.keywordWeight", "混合检索：关键词权重(0~1)"),
            Map.entry("retrieval.titleBonus", "混合检索：标题命中奖励(0~1)"),
            Map.entry("context.modelWindows", "上下文：模型窗口映射（模型名=token,逗号分隔）"),
            Map.entry("context.defaultWindowTokens", "上下文：模型默认窗口(token)"),
            Map.entry("context.safetyFactor", "上下文：窗口安全系数(0~1)"),
            Map.entry("context.costCapTokens", "上下文：成本软上限(token,0=不限制)"),
            Map.entry("context.maxOutputTokens", "上下文：输出限制(token)"),
            Map.entry("context.historyMaxTokens", "上下文：对话历史注入上限(token)"),
            Map.entry("context.historyPerMsgChars", "上下文：单条历史截断(字符)"),
            Map.entry("context.snippetWindowChars", "上下文：知识块命中片段窗口(字符,0=整块)"),
            Map.entry("context.maxContextHits", "上下文：知识块填充上限(块)"),
            Map.entry("deepReasoning.enabled", "深度思考：总开关"),
            Map.entry("deepReasoning.thinkingMode", "深度思考：思考模式(model/prompt)"),
            Map.entry("deepReasoning.enableThinking", "深度思考：透传 enable_thinking"),
            Map.entry("deepReasoning.prompt", "深度思考：思考引导提示词"),
            Map.entry("deepReasoning.searchTag", "深度思考：检索计划标签名"),
            Map.entry("deepReasoning.maxSubQueries", "深度思考：最大子问题数"),
            Map.entry("deepReasoning.multiRetrieval", "深度思考：多路并行检索开关"),
            Map.entry("deepReasoning.timeoutMillis", "深度思考：思考阶段超时(ms)"),
            Map.entry("deepReasoning.maxThinkingTokens", "深度思考：思考输出上限(token,0=不设)"),
            Map.entry("rerank.enabled", "重排：是否启用（需先启动本地 reranker 服务）"),
            Map.entry("rerank.baseUrl", "重排：服务地址"),
            Map.entry("rerank.model", "重排：模型名"),
            Map.entry("rerank.timeoutMillis", "重排：单次超时(ms)"));

    private final AiConfigMapper configMapper;
    private final AiAppProperties properties;
    private final Environment environment;
    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;

    /** 配置变更广播 channel（多实例同步：任意实例保存配置 → 其他实例订阅后重载缓存） */
    public static final String CONFIG_CHANNEL = "ai:config:changed";

    private volatile Map<String, String> cache = new HashMap<>();

    public ConfigService(AiConfigMapper configMapper, AiAppProperties properties, Environment environment,
                         StringRedisTemplate redisTemplate, RedisProperties redisProperties) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.environment = environment;
        this.redisTemplate = redisTemplate;
        this.redisProperties = redisProperties;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        // 缺失的默认项自动补入（存量升级场景：新增 key 自动注入，不覆盖已有配置）
        ensureDefaults();
        reload();
        startRedisConfigSync();
        log.info("模型配置加载完成，共 {} 项", cache.size());
    }

    /** 全量重读 c_ai_config 进缓存（本地更新 / Redis 订阅通知均调用） */
    public void reload() {
        try {
            List<AiConfig> all = configMapper.selectList(new LambdaQueryWrapper<AiConfig>());
            Map<String, String> map = new HashMap<>();
            for (AiConfig c : all) {
                map.put(c.getConfigKey(), c.getConfigValue());
            }
            cache = map;
        } catch (Exception e) {
            log.warn("[Config] 配置重载失败: {}", e.getMessage());
        }
    }

    /**
     * 多实例配置同步：daemon 线程订阅 Redis channel，任意实例保存配置后广播，
     * 本实例收到即全量重载缓存（保存即生效跨实例成立）。Redis 不可用时仅告警不影响启动。
     * 另起周期兜底 reload：订阅断线期间错过的变更由轮询补齐（每 5 分钟全量重读一次）。
     */
    private void startRedisConfigSync() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = new Jedis(redisProperties.getHost(), redisProperties.getPort(), 5000)) {
                    if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
                        jedis.auth(redisProperties.getPassword());
                    }
                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            reload();
                            log.info("[Config] 收到配置变更广播，已刷新缓存");
                        }
                    }, CONFIG_CHANNEL);
                } catch (Exception e) {
                    log.warn("[Config] Redis 配置同步订阅中断，5s 后重连: {}", e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "config-redis-sync");
        t.setDaemon(true);
        t.start();

        // 兜底轮询：订阅不可用时仍能收敛配置（防止长期不一致）
        Thread poll = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5 * 60 * 1000L);
                    reload();
                    log.debug("[Config] 周期兜底刷新配置缓存");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "config-sync-poll");
        poll.setDaemon(true);
        poll.start();
    }

    /** 本地保存后广播（其他实例订阅刷新；Redis 异常不影响保存结果） */
    private void publishConfigChanged() {
        try {
            redisTemplate.convertAndSend(CONFIG_CHANNEL, "changed");
        } catch (Exception e) {
            log.debug("[Config] 配置变更广播失败: {}", e.getMessage());
        }
    }

    /** 遍历 defaults()，DB 中缺失的 key 自动灌入默认值（单条失败不影响其余） */
    private void ensureDefaults() {
        for (Map.Entry<String, String> e : defaults().entrySet()) {
            try {
                Long cnt = configMapper.selectCount(new LambdaQueryWrapper<AiConfig>()
                        .eq(AiConfig::getConfigKey, e.getKey()));
                if (cnt == null || cnt == 0) {
                    AiConfig c = new AiConfig();
                    c.setConfigKey(e.getKey());
                    c.setConfigValue(e.getValue());
                    c.setRemark(EDITABLE.getOrDefault(e.getKey(), "只读配置"));
                    configMapper.insert(c);
                }
            } catch (Exception ex) {
                log.warn("配置默认值灌入失败: {} error={}", e.getKey(), ex.getMessage());
            }
        }
    }

    /** 从 yml/env 读取默认值 */
    private Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("chat.model", env("spring.ai.openai.chat.options.model", "qwen3.7-flash-2026-07-15"));
        d.put("chat.temperature", env("spring.ai.openai.chat.options.temperature", "0.3"));
        d.put("chat.systemPrompt", properties.getSystemPrompt());
        d.put("chat.baseUrl", env("spring.ai.openai.base-url", ""));
        d.put("chat.apiKey", env("spring.ai.openai.api-key", ""));
        d.put("vision.model", properties.getVision().getModel());
        d.put("vision.prompt", properties.getVision().getPrompt());
        d.put("vision.baseUrl", properties.getVision().getBaseUrl());
        d.put("vision.apiKey", properties.getVision().getApiKey());
        d.put("vision.concurrency", String.valueOf(properties.getVision().getConcurrency()));
        d.put("vision.descCacheVersion", "1");                 // 图片描述缓存版本（bump 后全量重新描述）
        d.put("vision.descCacheTtlDays", "180");               // 图片描述缓存有效期(天，0=不过期)
        d.put("embedding.model", env("spring.ai.openai.embedding.options.model", ""));
        d.put("chunk.maxChunks", String.valueOf(properties.getChunk().getMaxChunks()));
        d.put("chunk.maxImages", String.valueOf(properties.getChunk().getMaxImages()));
        d.put("chunk.overlap", String.valueOf(properties.getChunk().getOverlap()));
        d.put("chunk.structural", String.valueOf(properties.getChunk().isStructural()));
        d.put("chunk.structuralRatio", String.valueOf(properties.getChunk().getStructuralRatio()));
        d.put("upload.maxFileSize", String.valueOf(200L * 1024 * 1024));  // 业务上传上限（字节），默认 200MB
        d.put("retrieval.vectorWeight", String.valueOf(properties.getRetrieval().getVectorWeight()));
        d.put("retrieval.keywordWeight", String.valueOf(properties.getRetrieval().getKeywordWeight()));
        d.put("retrieval.titleBonus", String.valueOf(properties.getRetrieval().getTitleBonus()));
        d.put("rerank.enabled", String.valueOf(properties.getRetrieval().getRerank().isEnabled()));
        d.put("rerank.baseUrl", properties.getRetrieval().getRerank().getBaseUrl());
        d.put("rerank.model", properties.getRetrieval().getRerank().getModel());
        d.put("rerank.timeoutMillis", String.valueOf(properties.getRetrieval().getRerank().getTimeoutMillis()));
        d.put("context.modelWindows", properties.getContext().getModelWindows());
        d.put("context.defaultWindowTokens", String.valueOf(properties.getContext().getDefaultWindowTokens()));
        d.put("context.safetyFactor", String.valueOf(properties.getContext().getSafetyFactor()));
        d.put("context.costCapTokens", String.valueOf(properties.getContext().getCostCapTokens()));
        d.put("context.maxOutputTokens", String.valueOf(properties.getContext().getMaxOutputTokens()));
        d.put("context.historyMaxTokens", String.valueOf(properties.getContext().getHistoryMaxTokens()));
        d.put("context.historyPerMsgChars", String.valueOf(properties.getContext().getHistoryPerMsgChars()));
        d.put("context.snippetWindowChars", String.valueOf(properties.getContext().getSnippetWindowChars()));
        d.put("context.maxContextHits", String.valueOf(properties.getContext().getMaxContextHits()));
        d.put("deepReasoning.enabled", String.valueOf(properties.getDeepReasoning().isEnabled()));
        d.put("deepReasoning.thinkingMode", properties.getDeepReasoning().getThinkingMode());
        d.put("deepReasoning.enableThinking", String.valueOf(properties.getDeepReasoning().isEnableThinking()));
        d.put("deepReasoning.prompt", properties.getDeepReasoning().getPrompt());
        d.put("deepReasoning.searchTag", properties.getDeepReasoning().getSearchTag());
        d.put("deepReasoning.maxSubQueries", String.valueOf(properties.getDeepReasoning().getMaxSubQueries()));
        d.put("deepReasoning.multiRetrieval", String.valueOf(properties.getDeepReasoning().isMultiRetrieval()));
        d.put("deepReasoning.timeoutMillis", String.valueOf(properties.getDeepReasoning().getTimeoutMillis()));
        d.put("deepReasoning.maxThinkingTokens", String.valueOf(properties.getDeepReasoning().getMaxThinkingTokens()));
        // 检索行为参数（原硬编码收口，设置页可调、保存即生效）
        d.put("retrieval.vecThreshold", "0.3");            // 向量相似度归一化基准/下限
        d.put("retrieval.vectorTopK", "15");               // 向量召回 topK（调优/评估扫参用，下限 1）
        d.put("retrieval.keywordLimit", "20");             // 关键词召回上限
        d.put("retrieval.keywordTimeoutMs", "800");        // 关键词检索超时
        d.put("retrieval.searchTimeoutMs", "8000");        // 混合检索总超时
        d.put("retrieval.positionBonus", "0.03");          // 首块位置奖励
        d.put("retrieval.sectionBonus", "0.01");           // 前段位置奖励
        d.put("retrieval.keywordMaxTerms", "6");           // 关键词提取主词元上限
        d.put("retrieval.keywordMaxTotal", "12");          // 关键词提取总词元上限
        d.put("retrieval.vectorTopK", "15");               // 向量检索召回上限（评估批量对比可覆盖）
        // 重排行为参数
        d.put("rerank.minHits", "6");                      // 触发重排的候选下限
        d.put("rerank.maxHits", "15");                     // 触发重排的候选上限
        d.put("rerank.failCooldownMs", "60000");           // 重排失败后冷却再探测
        // 解析行为参数
        d.put("parse.concurrency", "2");                   // 文档解析并发数
        d.put("parse.ocrMinText", "20");                   // PDF 文本少于该长度判定扫描件触发 OCR
        d.put("vision.userImageConcurrency", "2");         // 用户上传图片识别并发
        // 问答行为参数
        d.put("chat.remainTokenFloor", "800");             // 上下文填充保留下限
        d.put("chat.truncateFallbackChars", "200");        // 超预算截断兜底字符数
        d.put("chat.historyRounds", "5");                  // 多轮记忆注入轮数
        d.put("chat.pipelineThreads", "8");                // 问答流水线线程数（重活不占 Tomcat 请求线程）
        return d;
    }

    private String env(String key, String def) {
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 评估批量对比用的线程局部参数覆盖（仅当前线程生效，finally 必须 clear；不写 DB 不污染配置） */
    private static final ThreadLocal<Map<String, String>> OVERRIDE = new ThreadLocal<>();

    /** 设置线程局部参数覆盖（评估用），返回 this 便于 finally 中 clearOverride */
    public void putOverrides(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) return;
        Map<String, String> cur = OVERRIDE.get();
        if (cur == null) {
            OVERRIDE.set(new HashMap<>(overrides));
        } else {
            cur.putAll(overrides);
        }
    }

    /** 清除线程局部参数覆盖（评估结束后必须调用） */
    public void clearOverride() {
        OVERRIDE.remove();
    }

    /** 读取配置（线程局部覆盖 → 缓存 → 默认值） */
    public String get(String key) {
        Map<String, String> ov = OVERRIDE.get();
        if (ov != null && ov.containsKey(key)) return ov.get(key);
        String v = cache.get(key);
        return v != null ? v : defaults().getOrDefault(key, "");
    }

    public double getDouble(String key) {
        try {
            return Double.parseDouble(get(key));
        } catch (Exception e) {
            return 0.3;
        }
    }

    public double getDouble(String key, double def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public int getInt(String key) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public int getInt(String key, int def) {
        String v = get(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public long getLong(String key) {
        try {
            return Long.parseLong(get(key).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    public boolean getBoolean(String key) {
        try {
            return Boolean.parseBoolean(get(key).trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** 保存可编辑项（白名单校验）→ 写 DB + 刷新缓存 */
    public Map<String, String> update(Map<String, Map<String, String>> groups) {
        Map<String, String> updates = new HashMap<>();
        if (groups != null) {
            for (Map.Entry<String, Map<String, String>> g : groups.entrySet()) {
                String prefix = g.getKey() + ".";
                for (Map.Entry<String, String> kv : g.getValue().entrySet()) {
                    String fullKey = prefix + kv.getKey();
                    if (EDITABLE.containsKey(fullKey)) {
                        updates.put(fullKey, kv.getValue() == null ? "" : kv.getValue().trim());
                    }
                }
            }
        }
        // 校验：仅当本次提交包含 chat.model 时才要求非空（避免只想改检索权重等其他项时被阻塞）
        String model = updates.get("chat.model");
        if (updates.containsKey("chat.model") && (model == null || model.isBlank())) {
            throw new IllegalArgumentException("chat.model 不能为空");
        }
        String temp = updates.get("chat.temperature");
        if (temp != null && !temp.isBlank()) {
            double t = Double.parseDouble(temp);
            if (t < 0 || t > 2) throw new IllegalArgumentException("temperature 需在 0~2 之间");
        }
        // 检索权重校验：必须是 0~1 的数字（防非法值导致检索排序异常）
        for (String wKey : new String[]{"retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.titleBonus", "context.safetyFactor", "chunk.structuralRatio"}) {
            String w = updates.get(wKey);
            if (w != null && !w.isBlank()) {
                try {
                    double v = Double.parseDouble(w);
                    if (v < 0 || v > 1) throw new IllegalArgumentException(wKey + " 需在 0~1 之间");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(wKey + " 必须是数字");
                }
            }
        }
        // 上下文长度参数校验：必须是非负整数
        for (String iKey : new String[]{"context.defaultWindowTokens", "context.costCapTokens", "context.maxOutputTokens",
                "context.historyMaxTokens", "context.historyPerMsgChars", "context.snippetWindowChars", "context.maxContextHits"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        // 深度思考参数校验
        String mode = updates.get("deepReasoning.thinkingMode");
        if (mode != null && !mode.isBlank() && !"model".equals(mode) && !"prompt".equals(mode)) {
            throw new IllegalArgumentException("deepReasoning.thinkingMode 仅允许 model / prompt");
        }
        for (String iKey : new String[]{"deepReasoning.maxSubQueries", "deepReasoning.timeoutMillis", "deepReasoning.maxThinkingTokens"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        for (String bKey : new String[]{"deepReasoning.enabled", "deepReasoning.enableThinking", "deepReasoning.multiRetrieval", "chunk.structural"}) {
            String v = updates.get(bKey);
            if (v != null && !v.isBlank() && !"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                throw new IllegalArgumentException(bKey + " 仅允许 true / false");
            }
        }
        // 重排参数校验
        String rb = updates.get("rerank.enabled");
        if (rb != null && !rb.isBlank() && !"true".equalsIgnoreCase(rb) && !"false".equalsIgnoreCase(rb)) {
            throw new IllegalArgumentException("rerank.enabled 仅允许 true / false");
        }
        String rt = updates.get("rerank.timeoutMillis");
        if (rt != null && !rt.isBlank()) {
            try {
                if (Integer.parseInt(rt.trim()) < 1000) throw new IllegalArgumentException("rerank.timeoutMillis 不能小于 1000");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("rerank.timeoutMillis 必须是整数");
            }
        }
        // 解析参数校验：非负整数（0 表示不限制）
        for (String iKey : new String[]{"chunk.maxChunks", "chunk.maxImages", "vision.concurrency"}) {
            String v = updates.get(iKey);
            if (v != null && !v.isBlank()) {
                try {
                    if (Integer.parseInt(v.trim()) < 0) throw new IllegalArgumentException(iKey + " 不能为负数");
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(iKey + " 必须是整数");
                }
            }
        }
        // 上传上限校验：必须 ≥1MB 且 ≤1GB（物理上限由 multipart 兜底）
        String uf = updates.get("upload.maxFileSize");
        if (uf != null && !uf.isBlank()) {
            try {
                long v = Long.parseLong(uf.trim());
                if (v < 1024 * 1024 || v > 1024L * 1024 * 1024) {
                    throw new IllegalArgumentException("upload.maxFileSize 需在 1MB ~ 1GB 之间");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("upload.maxFileSize 必须是整数(字节)");
            }
        }

        for (Map.Entry<String, String> kv : updates.entrySet()) {
            AiConfig c = configMapper.selectById(kv.getKey());
            if (c == null) {
                c = new AiConfig();
                c.setConfigKey(kv.getKey());
                c.setConfigValue(kv.getValue());
                c.setRemark(EDITABLE.get(kv.getKey()));
                configMapper.insert(c);
            } else {
                c.setConfigValue(kv.getValue());
                configMapper.updateById(c);
            }
        }
        // 刷新缓存
        Map<String, String> newCache = new HashMap<>(cache);
        newCache.putAll(updates);
        cache = newCache;
        log.info("模型配置已更新: {}", updates.keySet());
        // 广播其他实例刷新（多副本部署配置同步）
        publishConfigChanged();
        return updates;
    }

    /** 全量配置（供配置界面展示；apiKey 脱敏） */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] groups = {"chat", "vision", "embedding", "chunk", "upload", "retrieval", "rerank", "context", "deepReasoning"};
        for (String g : groups) {
            Map<String, Object> items = new LinkedHashMap<>();
            for (Map.Entry<String, String> d : defaults().entrySet()) {
                if (!d.getKey().startsWith(g + ".")) continue;
                String shortKey = d.getKey().substring(g.length() + 1);
                String value = get(d.getKey());
                if (shortKey.contains("apiKey") && value.length() > 4) {
                    value = "****" + value.substring(value.length() - 4);
                }
                items.put(shortKey, Map.of(
                        "value", value,
                        "editable", EDITABLE.containsKey(d.getKey())));
            }
            result.put(g, items);
        }
        return result;
    }
}

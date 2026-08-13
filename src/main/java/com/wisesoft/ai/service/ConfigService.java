package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiConfigMapper;
import com.wisesoft.ai.model.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

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
    private static final Map<String, String> EDITABLE = Map.of(
            "chat.model", "智能问答模型名",
            "chat.temperature", "回答温度(0~2)",
            "chat.systemPrompt", "AI助手系统提示词（角色与回答风格）",
            "vision.model", "视觉识别模型名",
            "vision.prompt", "视觉识别提示词",
            "retrieval.vectorWeight", "混合检索：向量权重(0~1)",
            "retrieval.keywordWeight", "混合检索：关键词权重(0~1)",
            "retrieval.titleBonus", "混合检索：标题命中奖励(0~1)");

    private final AiConfigMapper configMapper;
    private final AiAppProperties properties;
    private final Environment environment;

    private volatile Map<String, String> cache = new HashMap<>();

    public ConfigService(AiConfigMapper configMapper, AiAppProperties properties, Environment environment) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.environment = environment;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        // 缺失的默认项自动补入（存量升级场景：新增 key 自动注入，不覆盖已有配置）
        ensureDefaults();
        List<AiConfig> all = configMapper.selectList(new LambdaQueryWrapper<AiConfig>());
        Map<String, String> map = new HashMap<>();
        for (AiConfig c : all) {
            map.put(c.getConfigKey(), c.getConfigValue());
        }
        cache = map;
        log.info("模型配置加载完成，共 {} 项", map.size());
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
        d.put("embedding.model", env("spring.ai.openai.embedding.options.model", ""));
        d.put("retrieval.vectorWeight", String.valueOf(properties.getRetrieval().getVectorWeight()));
        d.put("retrieval.keywordWeight", String.valueOf(properties.getRetrieval().getKeywordWeight()));
        d.put("retrieval.titleBonus", String.valueOf(properties.getRetrieval().getTitleBonus()));
        return d;
    }

    private String env(String key, String def) {
        String v = environment.getProperty(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 读取配置（带默认兜底） */
    public String get(String key) {
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
        for (String wKey : new String[]{"retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.titleBonus"}) {
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
        return updates;
    }

    /** 全量配置（供配置界面展示；apiKey 脱敏） */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] groups = {"chat", "vision", "embedding", "retrieval"};
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

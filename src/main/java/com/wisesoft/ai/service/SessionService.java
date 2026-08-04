package com.wisesoft.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisesoft.ai.config.AiAppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理
 * Redis List 存储对话历史（RPUSH+LTRIM+EXPIRE Lua 原子操作，避免并发读改写丢失），
 * 消息结构 {role, content, images}，支持前端恢复历史时渲染 [图片N]
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String KEY_PREFIX = "dtbd:ai:session:";

    /**
     * 原子追加：RPUSH 消息 → LTRIM 保留最近 max 条 → 重置 EXPIRE
     */
    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>(
            "redis.call('RPUSH', KEYS[1], ARGV[1]);" +
                    "redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1);" +
                    "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]));" +
                    "return 1;", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AiAppProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话
     */
    public String createSession() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 获取会话完整历史
     */
    public List<Map<String, Object>> getHistory(String sessionId) {
        return readRange(sessionId, 0, -1);
    }

    /**
     * 获取最近 N 轮对话（每条消息含 role/content/images）
     */
    public List<Map<String, Object>> getRecentHistory(String sessionId, int rounds) {
        int limit = rounds * 2;
        return readRange(sessionId, -limit, -1);
    }

    /**
     * 追加消息（原子：RPUSH + LTRIM + EXPIRE）
     *
     * @param images 该轮回答关联的图片 URL 列表（可为空）
     */
    public void appendMessage(String sessionId, String role, String content, List<String> images) {
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("role", role);
            msg.put("content", content);
            if (images != null && !images.isEmpty()) {
                msg.put("images", images);
            }
            String json = objectMapper.writeValueAsString(msg);
            int max = properties.getSession().getMaxHistory() * 2;
            long expireSeconds = properties.getSession().getExpireMinutes() * 60L;
            redisTemplate.execute(APPEND_SCRIPT, Collections.singletonList(KEY_PREFIX + sessionId),
                    json, String.valueOf(max), String.valueOf(expireSeconds));
        } catch (Exception e) {
            log.warn("Failed to append session message: {}", e.getMessage());
        }
    }

    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    private List<Map<String, Object>> readRange(String sessionId, long start, long end) {
        List<String> jsons = redisTemplate.opsForList().range(KEY_PREFIX + sessionId, start, end);
        if (jsons == null || jsons.isEmpty()) return Collections.emptyList();
        try {
            return jsons.stream().map(j -> {
                try {
                    return objectMapper.readValue(j,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    return null;
                }
            }).filter(java.util.Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("Failed to read session history: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

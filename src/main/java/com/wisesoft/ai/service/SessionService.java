package com.wisesoft.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理
 * Redis 存储对话历史
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String KEY_PREFIX = "dtbd:ai:session:";

    private final StringRedisTemplate redisTemplate;
    private final com.wisesoft.ai.config.AiAppProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话
     */
    public String createSession() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 获取会话历史
     */
    public List<Map<String, String>> getHistory(String sessionId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 获取最近 N 轮对话
     */
    public List<Map<String, String>> getRecentHistory(String sessionId, int rounds) {
        List<Map<String, String>> history = getHistory(sessionId);
        if (history.isEmpty()) return history;
        int limit = rounds * 2;
        if (history.size() > limit) {
            return history.subList(history.size() - limit, history.size());
        }
        return history;
    }

    /**
     * 追加消息
     */
    public void appendMessage(String sessionId, String role, String content) {
        String key = KEY_PREFIX + sessionId;
        List<Map<String, String>> history = getHistory(sessionId);
        history.add(Map.of("role", role, "content", content));

        int max = properties.getSession().getMaxHistory() * 2;
        if (history.size() > max) {
            history = history.subList(history.size() - max, history.size());
        }

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(history),
                    properties.getSession().getExpireMinutes(), TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to save session: {}", e.getMessage());
        }
    }

    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}
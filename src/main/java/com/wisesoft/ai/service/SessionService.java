package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.dto.SessionInfo;
import com.wisesoft.ai.mapper.AiMessageMapper;
import com.wisesoft.ai.mapper.AiSessionMapper;
import com.wisesoft.ai.model.AiMessage;
import com.wisesoft.ai.model.AiSession;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 会话管理（MySQL + Redis 双层存储）
 * <p>
 * 写入：MySQL 持久化 → Redis 缓存（写穿透）
 * 读取：MySQL 优先 → Redis 兜底
 * 删除：MySQL 软删除 + Redis 清理
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String KEY_PREFIX = "ai-doc:session:";

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
    private final AiSessionMapper sessionMapper;
    private final AiMessageMapper messageMapper;

    /**
     * 创建新会话（MySQL + Redis）
     *
     * @param userId 归属用户（null/空白归入 anonymous 历史兼容池）
     */
    public String createSession(String userId) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        try {
            AiSession session = new AiSession();
            session.setId(sessionId);
            session.setUserId(normalizeUser(userId));
            session.setMessageCount(0);
            sessionMapper.insert(session);
        } catch (Exception e) {
            log.warn("创建会话 MySQL 写入失败: {}", e.getMessage());
        }
        return sessionId;
    }

    /**
     * 归属校验：会话不存在抛 404；存在但不属于该用户且非 anonymous 历史池则抛 403。
     * anonymous 名下的存量会话对所有用户可见（升级兼容），新建会话严格隔离。
     *
     * @return 校验通过的会话实体
     */
    public AiSession assertOwned(String sessionId, String userId) {
        AiSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new com.wisesoft.ai.common.BizException(404, "会话不存在或已被删除");
        }
        String owner = session.getUserId() == null || session.getUserId().isBlank()
                ? com.wisesoft.ai.util.UserContext.ANONYMOUS : session.getUserId();
        String uid = normalizeUser(userId);
        if (!owner.equals(uid) && !com.wisesoft.ai.util.UserContext.ANONYMOUS.equals(owner)) {
            throw new com.wisesoft.ai.common.BizException(403, "无权访问该会话");
        }
        return session;
    }

    /** 用户标识规范化：空值归 anonymous */
    private String normalizeUser(String userId) {
        return userId == null || userId.isBlank()
                ? com.wisesoft.ai.util.UserContext.ANONYMOUS : userId;
    }

    /**
     * 确保指定 ID 的会话存在且归属当前用户（chat 传入不存在/已被删除的 sessionId 时补建，兼容旧客户端行为）
     *
     * @return 校验/补建后的会话 ID
     */
    public String ensureSession(String sessionId, String userId) {
        AiSession session = new AiSession();
        session.setId(sessionId);
        session.setUserId(normalizeUser(userId));
        session.setMessageCount(0);
        try {
            sessionMapper.insert(session);
        } catch (Exception e) {
            // 并发下同 ID 重复插入等异常：仅记录，聊天流程继续（消息追加不依赖会话记录存在）
            log.warn("补建会话记录失败 (session={}): {}", sessionId, e.getMessage());
        }
        return sessionId;
    }

    /**
     * 查询会话列表（仅当前用户 + anonymous 历史兼容池；支持关键词搜索），置顶优先、按更新时间倒序
     *
     * @param keyword 可选，按标题或消息内容模糊匹配；空/空白返回全量
     */
    public List<SessionInfo> listSessions(String userId, String keyword) {
        try {
            LambdaQueryWrapper<AiSession> wrapper = new LambdaQueryWrapper<>();
            // 只看自己的会话 + anonymous 历史兼容池（存量升级数据）
            wrapper.and(w -> w.eq(AiSession::getUserId, normalizeUser(userId))
                    .or().eq(AiSession::getUserId, com.wisesoft.ai.util.UserContext.ANONYMOUS));
            if (keyword != null && !keyword.isBlank()) {
                String esc = escapeLike(keyword.trim());
                wrapper.and(w -> w.like(AiSession::getTitle, keyword.trim())
                        .or().inSql(AiSession::getId,
                                "SELECT DISTINCT session_id FROM c_ai_message WHERE deleted=0 AND content LIKE '%"
                                        + esc + "%'"));
            }
            wrapper.orderByDesc(AiSession::getIsPinned)
                    .orderByDesc(AiSession::getUpdateTime);
            return sessionMapper.selectList(wrapper).stream().map(s -> {
                SessionInfo info = new SessionInfo();
                info.setId(s.getId());
                info.setTitle(s.getTitle());
                info.setMessageCount(s.getMessageCount());
                info.setUpdateTime(s.getUpdateTime());
                info.setIsPinned(s.getIsPinned());
                info.setIsFavorite(s.getIsFavorite());
                return info;
            }).toList();
        } catch (Exception e) {
            log.warn("查询会话列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 置顶/取消置顶会话（校验归属）
     */
    public void updatePin(String userId, String sessionId, boolean pinned) {
        assertOwned(sessionId, userId);
        updateFlag(sessionId, pinned, "is_pinned", AiSession::setIsPinned);
    }

    /**
     * 收藏/取消收藏会话（校验归属）
     */
    public void updateFavorite(String userId, String sessionId, boolean favorite) {
        assertOwned(sessionId, userId);
        updateFlag(sessionId, favorite, "is_favorite", AiSession::setIsFavorite);
    }

    /**
     * 通用布尔标志更新（置顶/收藏）
     */
    private void updateFlag(String sessionId, boolean flag, String fieldName,
                            java.util.function.BiConsumer<AiSession, Integer> setter) {
        AiSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new com.wisesoft.ai.common.BizException("会话不存在");
        }
        AiSession update = new AiSession();
        update.setId(sessionId);
        setter.accept(update, flag ? 1 : 0);
        sessionMapper.updateById(update);
    }

    /**
     * 转义 LIKE 特殊字符（% _ \）+ SQL 单引号（''），防止搜索词干扰匹配或注入 inSql 拼接
     */
    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("'", "''")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * 软删除会话（校验归属；MySQL 逻辑删除会话+消息 + Redis 清理）
     */
    public void deleteSession(String userId, String sessionId) {
        assertOwned(sessionId, userId);
        try {
            sessionMapper.deleteById(sessionId);
            // 同步软删除该会话下的所有消息
            LambdaUpdateWrapper<AiMessage> wrapper = new LambdaUpdateWrapper<>();
            wrapper.set(AiMessage::getDeleted, 1)
                    .eq(AiMessage::getSessionId, sessionId);
            messageMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("删除会话 MySQL 操作失败: {}", e.getMessage());
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            log.warn("删除会话 Redis 清理失败: {}", e.getMessage());
        }
    }

    /**
     * 获取会话完整历史（MySQL 优先，Redis 兜底）
     */
    public List<Map<String, Object>> getHistory(String sessionId) {
        List<Map<String, Object>> fromMysql = readFromMysql(sessionId);
        if (!fromMysql.isEmpty()) {
            return fromMysql;
        }
        // MySQL 无数据，从 Redis 读取
        List<Map<String, Object>> fromRedis = readRange(sessionId, 0, -1);
        if (!fromRedis.isEmpty()) {
            // 异步 backfill 到 MySQL（新线程，不阻塞当前请求）
            backfillToMysql(sessionId, fromRedis);
        }
        return fromRedis;
    }

    /**
     * 获取最近 N 轮对话（MySQL 直读最近 N 轮，Redis 兜底）
     * 返回 null 表示"读取失败"（fail-loud：调用方需区分"无历史"与"历史读取失败"，不得静默当无历史）
     */
    public List<Map<String, Object>> getRecentHistory(String sessionId, int rounds) {
        List<Map<String, Object>> recent = readRecentFromMysql(sessionId, rounds);
        if (recent == null) return null; // 读失败：显式失败信号，调用方 fail-loud
        if (!recent.isEmpty()) {
            return recent;
        }
        // MySQL 无数据，从 Redis 读取（Redis 已按 maxHistory 裁剪）
        List<Map<String, Object>> fromRedis = readRange(sessionId, 0, -1);
        if (!fromRedis.isEmpty()) {
            // 异步 backfill 到 MySQL（新线程，不阻塞当前请求）
            backfillToMysql(sessionId, fromRedis);
        }
        return fromRedis;
    }

    /**
     * 直读最近 N 轮对话：ORDER BY sequence DESC LIMIT rounds*2 后反转，
     * 避免全量读取该会话所有消息（每次问答该路径被调 2~3 次，会话越长差异越大）
     */
    private List<Map<String, Object>> readRecentFromMysql(String sessionId, int rounds) {
        int limit = Math.max(1, rounds * 2);
        try {
            LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AiMessage::getSessionId, sessionId)
                    .orderByDesc(AiMessage::getSequence)
                    .last("LIMIT " + limit);
            List<AiMessage> messages = messageMapper.selectList(wrapper);
            if (messages.isEmpty()) return Collections.emptyList();
            Collections.reverse(messages); // 恢复时间正序（最新在后）
            return messages.stream().map(this::toMessageMap).collect(Collectors.toList());
        } catch (Exception e) {
            // M6 fail-loud：读失败返回 null（区分"无历史"与"读取失败"），调用方上报而非静默跳过记忆
            log.warn("[FAIL-LOUD] MySQL 读取最近会话历史失败 (session={}): {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 追加消息（MySQL 持久化 → Redis 缓存），返回消息 ID（用于反馈关联）
     *
     * @param images   该轮回答关联的图片 URL 列表（可为空）
     * @param sources  引用来源 JSON 数组字符串（可为空），如 [{"ref":1,"knowledgeId":..,"docId":..,"fileName":..,"title":..,"snippet":..}]
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources) {
        return appendMessage(sessionId, role, content, images, sources, null);
    }

    /**
     * 追加消息（含深度思考全文）：
     * MySQL 优先持久化，失败降级 Redis 缓存（Lua 原子追加 + 上限裁剪 + TTL）
     *
     * @param thinking 思考过程全文（深度思考，可为空）
     */
    public String appendMessage(String sessionId, String role, String content, List<String> images, String sources, String thinking) {
        // 1. MySQL 持久化
        try {
            // 获取下一序号
            int nextSeq = getNextSequence(sessionId);

            // 插入消息
            AiMessage msg = new AiMessage();
            msg.setSessionId(sessionId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setThinking(thinking);
            msg.setImages(images != null && !images.isEmpty() ? JSON.toJSONString(images) : null);
            msg.setSources(sources);
            msg.setSequence(nextSeq);
            messageMapper.insert(msg);

            // 更新会话：title（取首条用户问题前50字）、message_count、update_time
            AiSession update = new AiSession();
            update.setId(sessionId);
            update.setMessageCount(nextSeq); // sequence 自增即消息数
            update.setUpdateTime(null); // MyBatis-Plus 会忽略 null，用 ON UPDATE CURRENT_TIMESTAMP
            if (role.equals("user")) {
                // 仅在 title 为空时设置（首条用户消息）
                AiSession existing = sessionMapper.selectById(sessionId);
                if (existing != null && existing.getTitle() == null) {
                    String title = content.length() > 50 ? content.substring(0, 50) : content;
                    update.setTitle(title.trim());
                }
            }
            sessionMapper.updateById(update);
            return msg.getId();
        } catch (Exception e) {
            log.warn("MySQL 追加消息失败 (session={}): {}", sessionId, e.getMessage());
        }

        // 2. Redis 缓存（MySQL 失败也不影响 Redis 写入，保证前端体验）
        try {
            Map<String, Object> redisMsg = new HashMap<>();
            redisMsg.put("role", role);
            redisMsg.put("content", content);
            if (thinking != null && !thinking.isBlank()) {
                redisMsg.put("thinking", thinking);
            }
            if (images != null && !images.isEmpty()) {
                redisMsg.put("images", images);
            }
            if (sources != null && !sources.isBlank()) {
                try {
                    redisMsg.put("sources", JSON.parseArray(sources, Map.class));
                } catch (Exception ignored) {
                }
            }
            String json = objectMapper.writeValueAsString(redisMsg);
            int max = properties.getSession().getMaxHistory() * 2;
            long expireSeconds = properties.getSession().getExpireMinutes() * 60L;
            redisTemplate.execute(APPEND_SCRIPT, Collections.singletonList(KEY_PREFIX + sessionId),
                    json, String.valueOf(max), String.valueOf(expireSeconds));
        } catch (Exception e) {
            log.warn("Redis 追加消息失败 (session={}): {}", sessionId, e.getMessage());
        }
        return null;
    }

    /**
     * 清除 Redis 缓存（保留 MySQL 数据）
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    /**
     * 清空当前用户的会话（anonymous 用户清空匿名池；MySQL 软删 + Redis 清理）
     */
    public void clearAll(String userId) {
        String uid = normalizeUser(userId);
        try {
            // 先取该用户名下的会话 ID（Redis 按 sessionId 清理需要）
            List<AiSession> own = sessionMapper.selectList(new LambdaQueryWrapper<AiSession>()
                    .eq(AiSession::getUserId, uid));
            List<String> ownIds = own.stream().map(AiSession::getId).toList();
            // 逻辑删除该用户的会话与其下所有消息
            if (!ownIds.isEmpty()) {
                messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                        .in(AiMessage::getSessionId, ownIds));
            }
            sessionMapper.delete(new LambdaQueryWrapper<AiSession>()
                    .eq(AiSession::getUserId, uid));
            // 清理该用户的 Redis 会话 key
            if (!ownIds.isEmpty()) {
                Set<String> keys = ownIds.stream().map(KEY_PREFIX::concat).collect(java.util.stream.Collectors.toSet());
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("清空会话 MySQL 操作失败: {}", e.getMessage());
        }
    }

    /**
     * 删除一轮对话（对话组）：指定回答（assistant）消息 ID，连同其前面的用户问题一起软删除。
     * 序号约定：同轮用户问题 seq=k、回答 seq=k+1 连续；按 seq-1+role=user 精确配对防误删。
     * 同步：会话消息计数递减（best-effort）+ Redis 兜底缓存失效（下次读回退 MySQL）。
     *
     * @return 实际删除条数
     */
    public int deleteRound(String sessionId, String assistantMessageId) {
        AiMessage assistant = messageMapper.selectById(assistantMessageId);
        if (assistant == null || !sessionId.equals(assistant.getSessionId())) return 0;
        List<String> ids = new ArrayList<>();
        ids.add(assistant.getId());
        AiMessage userMsg = messageMapper.selectOne(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId)
                .eq(AiMessage::getSequence, assistant.getSequence() - 1)
                .eq(AiMessage::getRole, "user")
                .last("LIMIT 1"));
        if (userMsg != null) ids.add(userMsg.getId());
        int deleted = 0;
        for (String id : ids) {
            deleted += messageMapper.deleteById(id); // @TableLogic 软删除
        }
        // 会话消息计数递减（best-effort，仅影响侧边栏展示）
        try {
            AiSession session = sessionMapper.selectById(sessionId);
            if (session != null && session.getMessageCount() != null) {
                AiSession upd = new AiSession();
                upd.setId(sessionId);
                upd.setMessageCount(Math.max(0, session.getMessageCount() - ids.size()));
                sessionMapper.updateById(upd);
            }
        } catch (Exception ignored) {
        }
        clearSession(sessionId);
        return deleted;
    }

    /**
     * 撤销删除一轮对话：按回答消息 ID 恢复该轮（回答 + 同组用户问题）。
     * 自定义 SQL 绕过 @TableLogic 定位/恢复已软删消息；同步计数加回 + Redis 失效。
     *
     * @return 实际恢复条数（消息可能已被物理清理，恢复 0 条时前端提示已过撤销期）
     */
    public int undoDeleteRound(String sessionId, String assistantMessageId) {
        AiMessage assistant = messageMapper.selectByIdIgnoreDeleted(assistantMessageId);
        if (assistant == null || !sessionId.equals(assistant.getSessionId())) return 0;
        // 已处于未删除状态 = 撤销已被执行过（重复撤销防御，防计数重复加回）
        if (assistant.getDeleted() == null || assistant.getDeleted() == 0) return 0;
        int restored = messageMapper.restoreById(assistant.getId());
        AiMessage userMsg = messageMapper.selectBySeqIgnoreDeleted(sessionId,
                assistant.getSequence() - 1, "user");
        if (userMsg != null) {
            restored += messageMapper.restoreById(userMsg.getId());
        }
        // 计数加回（best-effort）
        try {
            AiSession session = sessionMapper.selectById(sessionId);
            if (session != null) {
                AiSession upd = new AiSession();
                upd.setId(sessionId);
                int base = session.getMessageCount() == null ? 0 : session.getMessageCount();
                upd.setMessageCount(base + (userMsg != null ? 2 : 1));
                sessionMapper.updateById(upd);
            }
        } catch (Exception ignored) {
        }
        clearSession(sessionId);
        return restored;
    }

    // ==================== 私有方法 ====================

    /**
     * 从 MySQL 读取会话历史
     */
    private List<Map<String, Object>> readFromMysql(String sessionId) {
        try {
            LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AiMessage::getSessionId, sessionId)
                    .orderByAsc(AiMessage::getSequence);
            List<AiMessage> messages = messageMapper.selectList(wrapper);
            if (messages.isEmpty()) return Collections.emptyList();

            return messages.stream().map(this::toMessageMap).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("MySQL 读取会话历史失败 (session={}): {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 消息实体 → 前端展示 Map（含思考/图片/引用来源；字段名与 SSE done 事件一致）
     */
    private Map<String, Object> toMessageMap(AiMessage m) {
        Map<String, Object> map = new HashMap<>();
        map.put("role", m.getRole());
        map.put("content", m.getContent());
        map.put("messageId", m.getId()); // 与 SSE done 事件字段名一致，供前端反馈/导出等操作
        map.put("createTime", m.getCreateTime()); // 气泡下方时间展示
        if (m.getThinking() != null && !m.getThinking().isBlank()) {
            map.put("thinking", m.getThinking());
        }
        if (m.getImages() != null && !m.getImages().isBlank()) {
            try {
                map.put("images", JSON.parseArray(m.getImages(), String.class));
            } catch (Exception e) {
                // images 解析失败忽略
            }
        }
        if (m.getSources() != null && !m.getSources().isBlank()) {
            try {
                map.put("sources", JSON.parseArray(m.getSources(), Map.class));
            } catch (Exception e) {
                // sources 解析失败忽略
            }
        }
        return map;
    }

    /**
     * 获取会话的下一条消息序号
     */
    private int getNextSequence(String sessionId) {
        try {
            LambdaQueryWrapper<AiMessage> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AiMessage::getSessionId, sessionId)
                    .orderByDesc(AiMessage::getSequence)
                    .last("LIMIT 1");
            List<AiMessage> list = messageMapper.selectList(wrapper);
            return list.isEmpty() ? 1 : list.get(0).getSequence() + 1;
        } catch (Exception e) {
            log.warn("查询消息序号失败: {}", e.getMessage());
            return 1;
        }
    }

    /**
     * 从 Redis 读取指定范围的消息
     */
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
            }).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            log.warn("Failed to read session history: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将 Redis 数据异步补写到 MySQL（线程池后台执行，不阻塞会话读取）
     */
    private void backfillToMysql(String sessionId, List<Map<String, Object>> messages) {
        ThreadPoolManager.execute(() -> {
            try {
                // 检查 MySQL 是否已有数据，避免重复 backfill
                LambdaQueryWrapper<AiMessage> check = new LambdaQueryWrapper<>();
                check.eq(AiMessage::getSessionId, sessionId)
                        .last("LIMIT 1");
                if (!messageMapper.selectList(check).isEmpty()) {
                    return; // 已有数据，跳过
                }

                // 确保 session 记录存在
                AiSession existing = sessionMapper.selectById(sessionId);
                if (existing == null) {
                    AiSession session = new AiSession();
                    session.setId(sessionId);
                    session.setMessageCount(0);
                    sessionMapper.insert(session);
                }

                int seq = 0;
                String title = null;
                for (Map<String, Object> m : messages) {
                    String role = String.valueOf(m.getOrDefault("role", ""));
                    String content = String.valueOf(m.getOrDefault("content", ""));
                    Object imagesObj = m.get("images");
                    String imagesJson = null;
                    if (imagesObj instanceof List<?> list && !list.isEmpty()) {
                        imagesJson = JSON.toJSONString(list);
                    }

                    AiMessage msg = new AiMessage();
                    msg.setSessionId(sessionId);
                    msg.setRole(role);
                    msg.setContent(content);
                    msg.setImages(imagesJson);
                    Object thinkingObj = m.get("thinking");
                    if (thinkingObj != null) {
                        msg.setThinking(String.valueOf(thinkingObj));
                    }
                    Object sourcesObj = m.get("sources");
                    if (sourcesObj != null) {
                        msg.setSources(JSON.toJSONString(sourcesObj));
                    }
                    msg.setSequence(++seq);
                    messageMapper.insert(msg);

                    if (title == null && "user".equals(role)) {
                        title = content.length() > 50 ? content.substring(0, 50).trim() : content.trim();
                    }
                }

                // 更新 session 的 title 和 message_count
                if (title != null || seq > 0) {
                    AiSession update = new AiSession();
                    update.setId(sessionId);
                    update.setMessageCount(seq);
                    if (title != null) update.setTitle(title);
                    sessionMapper.updateById(update);
                }

                log.info("Backfill 完成: session={}, messages={}", sessionId, seq);
            } catch (Exception e) {
                log.warn("Backfill 失败 (session={}): {}", sessionId, e.getMessage());
            }
        });
    }
}

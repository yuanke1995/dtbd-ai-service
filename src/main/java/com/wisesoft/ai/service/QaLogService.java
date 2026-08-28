package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.AiQaFeedbackMapper;
import com.wisesoft.ai.mapper.AiQaLogMapper;
import com.wisesoft.ai.model.AiQaFeedback;
import com.wisesoft.ai.model.AiQaLog;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 问答数据闭环服务
 * - 落日志（异步，不阻塞 SSE 流式响应）
 * - 反馈 upsert（message_id 唯一）
 * - 统计看板聚合（热门问题 / 无命中率 / 反馈）
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaLogService {

    private final AiQaLogMapper qaLogMapper;
    private final AiQaFeedbackMapper feedbackMapper;

    /**
     * 异步落问答日志（不阻塞主流程）
     */
    public void logAsync(String sessionId, String question, String answer,
                         List<String> hitDocIds, boolean hasCitation, long elapsedMs,
                         String rewrittenQuery) {
        ThreadPoolManager.execute(() -> {
            try {
                AiQaLog log = new AiQaLog();
                log.setSessionId(sessionId);
                log.setQuestion(question == null ? "" : question.length() > 500 ? question.substring(0, 500) : question);
                log.setAnswerSummary(summary(answer));
                log.setHitDocIds(hitDocIds == null || hitDocIds.isEmpty()
                        ? null : hitDocIds.stream().distinct().collect(Collectors.joining(",")));
                log.setRewrittenQuery(rewrittenQuery == null || rewrittenQuery.equals(question)
                        ? null : (rewrittenQuery.length() > 500 ? rewrittenQuery.substring(0, 500) : rewrittenQuery));
                log.setHasCitation(hasCitation ? 1 : 0);
                log.setElapsedMs((int) Math.min(elapsedMs, Integer.MAX_VALUE));
                qaLogMapper.insert(log);
            } catch (Exception e) {
                // L6 fail-loud：问答日志是反馈看板/知识缺口的数据源，丢失升级为 error（含 sessionId 便于排查）
                log.error("[FAIL-LOUD] 问答日志写入失败 session={}: {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 提交/更新反馈（按 message_id upsert）
     */
    public void feedback(String messageId, int rating, String feedbackText) {
        if (messageId == null || messageId.isBlank()) {
            throw new com.wisesoft.ai.common.BizException("缺少 messageId");
        }
        if (rating != 0 && rating != 1) {
            throw new com.wisesoft.ai.common.BizException("rating 仅支持 0/1");
        }
        AiQaFeedback existing = feedbackMapper.selectOne(
                new LambdaQueryWrapper<AiQaFeedback>().eq(AiQaFeedback::getMessageId, messageId).last("limit 1"));
        if (existing != null) {
            existing.setRating(rating);
            existing.setFeedbackText(feedbackText);
            feedbackMapper.updateById(existing);
        } else {
            AiQaFeedback f = new AiQaFeedback();
            f.setMessageId(messageId);
            f.setRating(rating);
            f.setFeedbackText(feedbackText);
            feedbackMapper.insert(f);
        }
    }

    /**
     * 统计看板：热门问题 / 无命中 / 反馈（全部走 SQL 聚合，避免全表拉取到内存）
     */
    public Map<String, Object> analyticsSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int limit = 2000; // 与原先 LIMIT 2000 语义一致
            Map<String, Object> stats = qaLogMapper.summaryStats(limit);
            long total = num(stats.get("total"));
            long noHit = num(stats.get("no_hit"));
            long cited = num(stats.get("cited"));

            result.put("topQuestions", qaLogMapper.topQuestions(limit).stream()
                    .map(m -> Map.of("question", String.valueOf(m.get("question")),
                            "count", num(m.get("cnt"))))
                    .toList());
            result.put("noHitRate", total == 0 ? 0 : Math.round(noHit * 1000.0 / total) / 10.0);
            result.put("total", total);
            result.put("citationRate", total == 0 ? 0 : Math.round(cited * 1000.0 / total) / 10.0);
            result.put("noHitQuestions", qaLogMapper.noHitQuestions(limit).stream()
                    .map(m -> Map.of("question", String.valueOf(m.get("question")),
                            "count", num(m.get("cnt"))))
                    .toList());

            // 反馈统计（COUNT 聚合，不拉全表）
            Map<String, Object> fb = feedbackMapper.feedbackStats();
            long likes = num(fb.get("likes"));
            long fbTotal = num(fb.get("total"));
            result.put("feedback", Map.of(
                    "total", fbTotal,
                    "likes", likes,
                    "dislikes", fbTotal - likes,
                    "likeRate", fbTotal == 0 ? 0 : Math.round(likes * 1000.0 / fbTotal) / 10.0));
        } catch (Exception e) {
            log.warn("统计聚合失败: {}", e.getMessage());
        }
        return result;
    }

    /** SQL 聚合返回的数值统一转 long（COUNT 为 Long，SUM(CASE...) 为 BigDecimal） */
    private long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 获取无命中问题列表（按频次降序，含最近提问时间），供前端一键入库
     */
    public List<Map<String, Object>> listUnmatched() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = qaLogMapper.noHitTop(LocalDateTime.now().minusDays(30));
            for (Map<String, Object> m : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", String.valueOf(m.get("question")));
                item.put("count", num(m.get("cnt")));
                Object latest = m.get("latest");
                item.put("latestTime", latest == null ? "" : String.valueOf(latest));
                result.add(item);
            }
        } catch (Exception e) {
            log.warn("查询无命中问题失败: {}", e.getMessage());
        }
        return result;
    }

    private String summary(String answer) {
        if (answer == null) return "";
        String s = answer.replaceAll("<related>[\\s\\S]*?</related>", "").replaceAll("\\[图片[^\\]]*\\]", " ").trim();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}

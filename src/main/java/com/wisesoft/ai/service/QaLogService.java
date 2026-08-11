package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.AiQaFeedbackMapper;
import com.wisesoft.ai.mapper.AiQaLogMapper;
import com.wisesoft.ai.model.AiQaFeedback;
import com.wisesoft.ai.model.AiQaLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
                         List<String> hitDocIds, boolean hasCitation, long elapsedMs) {
        CompletableFuture.runAsync(() -> {
            try {
                AiQaLog log = new AiQaLog();
                log.setSessionId(sessionId);
                log.setQuestion(question == null ? "" : question.length() > 500 ? question.substring(0, 500) : question);
                log.setAnswerSummary(summary(answer));
                log.setHitDocIds(hitDocIds == null || hitDocIds.isEmpty()
                        ? null : hitDocIds.stream().distinct().collect(Collectors.joining(",")));
                log.setHasCitation(hasCitation ? 1 : 0);
                log.setElapsedMs((int) Math.min(elapsedMs, Integer.MAX_VALUE));
                qaLogMapper.insert(log);
            } catch (Exception e) {
                log.warn("问答日志写入失败: {}", e.getMessage());
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
     * 统计看板：热门问题 / 无命中 / 反馈
     */
    public Map<String, Object> analyticsSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<AiQaLog> logs = qaLogMapper.selectList(
                    new LambdaQueryWrapper<AiQaLog>().orderByDesc(AiQaLog::getCreatedAt).last("LIMIT 2000"));

            // 热门问题 TOP10（按问题文本聚合）
            Map<String, Long> qCount = logs.stream()
                    .filter(l -> l.getQuestion() != null && !l.getQuestion().isBlank())
                    .collect(Collectors.groupingBy(AiQaLog::getQuestion, Collectors.counting()));
            result.put("topQuestions", qCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> Map.of("question", e.getKey(), "count", e.getValue()))
                    .toList());

            // 无命中问题 TOP10（hitDocIds 为空）
            List<String> noHit = logs.stream()
                    .filter(l -> l.getHitDocIds() == null || l.getHitDocIds().isBlank())
                    .map(AiQaLog::getQuestion)
                    .filter(q -> q != null && !q.isBlank())
                    .toList();
            result.put("noHitRate", logs.isEmpty() ? 0 : Math.round(noHit.size() * 1000.0 / logs.size()) / 10.0);
            result.put("total", logs.size());
            long cited = logs.stream().filter(l -> l.getHasCitation() != null && l.getHasCitation() == 1).count();
            result.put("citationRate", logs.isEmpty() ? 0 : Math.round(cited * 1000.0 / logs.size()) / 10.0);
            Map<String, Long> noHitCount = noHit.stream().collect(Collectors.groupingBy(q -> q, Collectors.counting()));
            result.put("noHitQuestions", noHitCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(10)
                    .map(e -> Map.of("question", e.getKey(), "count", e.getValue()))
                    .toList());

            // 反馈统计
            List<AiQaFeedback> feedbacks = feedbackMapper.selectList(null);
            long likes = feedbacks.stream().filter(f -> f.getRating() != null && f.getRating() == 1).count();
            long dislikes = feedbacks.size() - likes;
            result.put("feedback", Map.of(
                    "total", feedbacks.size(),
                    "likes", likes,
                    "dislikes", dislikes,
                    "likeRate", feedbacks.isEmpty() ? 0 : Math.round(likes * 1000.0 / feedbacks.size()) / 10.0));
        } catch (Exception e) {
            log.warn("统计聚合失败: {}", e.getMessage());
        }
        return result;
    }

    private String summary(String answer) {
        if (answer == null) return "";
        String s = answer.replaceAll("<related>[\\s\\S]*?</related>", "").replaceAll("\\[图片[^\\]]*\\]", " ").trim();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}

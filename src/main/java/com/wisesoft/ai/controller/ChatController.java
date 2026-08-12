package com.wisesoft.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.dto.ChatRequest;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.dto.SessionInfo;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.service.ImageUrlSigner;
import com.wisesoft.ai.service.RagService;
import com.wisesoft.ai.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 聊天控制器（SSE 流式）
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final RagService ragService;
    private final SessionService sessionService;
    private final ImageUrlSigner imageUrlSigner;
    private final AiKnowledgeMapper knowledgeMapper;

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody @Valid ChatRequest request) {
        String question = request.getQuestion().trim();

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionService.createSession();
        }

        SseEmitter emitter = new SseEmitter(300000L);
        ragService.chat(sessionId, question, emitter);
        return emitter;
    }

    /**
     * 列出所有会话（按更新时间倒序）
     */
    @GetMapping("/sessions")
    public ResultJson listSessions() {
        List<SessionInfo> sessions = sessionService.listSessions();
        return ResultJson.ok(sessions);
    }

    @GetMapping("/session/{sessionId}")
    public ResultJson getHistory(@PathVariable String sessionId) {
        List<Map<String, Object>> history = sessionService.getHistory(sessionId);
        // 历史图片存的是原始 URL，响应时动态签名（避免签名过期导致恢复会话图片 401）
        for (Map<String, Object> msg : history) {
            Object imgs = msg.get("images");
            if (imgs instanceof List<?> list && !list.isEmpty()) {
                msg.put("images", list.stream()
                        .map(String::valueOf)
                        .map(imageUrlSigner::signUrl)
                        .toList());
            }
        }
        return ResultJson.ok(history);
    }

    /**
     * 删除会话（MySQL 软删除 + Redis 清理）
     */
    @DeleteMapping("/session/{sessionId}")
    public ResultJson deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResultJson.ok("会话已删除");
    }

    /**
     * 清空所有会话
     */
    @DeleteMapping("/sessions")
    public ResultJson clearAllSessions() {
        sessionService.clearAll();
        return ResultJson.ok("所有会话已清空");
    }

    @PostMapping("/session/new")
    public ResultJson newSession() {
        return ResultJson.ok(Map.of("sessionId", sessionService.createSession()));
    }

    /**
     * 知识块详情（引用溯源：来源弹窗展示知识块全文）
     */
    @GetMapping("/knowledge/{knowledgeId}")
    public ResultJson knowledgeDetail(@PathVariable String knowledgeId) {
        AiKnowledge k = knowledgeMapper.selectById(knowledgeId);
        if (k == null) {
            return ResultJson.error(404, "知识块不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("docId", k.getDocId());
        m.put("title", k.getTitle());
        m.put("content", k.getContent());
        m.put("chunkIndex", k.getChunkIndex());
        m.put("images", (k.getImages() == null || k.getImages().isBlank())
                ? List.of() : JSON.parseArray(k.getImages(), String.class));
        return ResultJson.ok(m);
    }
}

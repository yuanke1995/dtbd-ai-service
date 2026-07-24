package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ChatRequest;
import com.wisesoft.ai.service.RagService;
import com.wisesoft.ai.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        String question = request.getQuestion();
        if (question == null || question.trim().isEmpty()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"type\":\"error\",\"content\":\"请输入问题\",\"sessionId\":\"\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionService.createSession();
        }

        SseEmitter emitter = new SseEmitter(300000L);
        ragService.chat(sessionId, question.trim(), emitter);
        return emitter;
    }

    @GetMapping("/session/{sessionId}")
    public com.wisesoft.ai.dto.ResultJson getHistory(@PathVariable String sessionId) {
        return com.wisesoft.ai.dto.ResultJson.ok(sessionService.getHistory(sessionId));
    }

    @DeleteMapping("/session/{sessionId}")
    public com.wisesoft.ai.dto.ResultJson clearSession(@PathVariable String sessionId) {
        sessionService.clearSession(sessionId);
        return com.wisesoft.ai.dto.ResultJson.ok("会话已清除");
    }

    @PostMapping("/session/new")
    public com.wisesoft.ai.dto.ResultJson newSession() {
        return com.wisesoft.ai.dto.ResultJson.ok(Map.of("sessionId", sessionService.createSession()));
    }
}
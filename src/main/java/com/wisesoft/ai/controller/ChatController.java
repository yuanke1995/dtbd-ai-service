package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ChatRequest;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ImageUrlSigner;
import com.wisesoft.ai.service.RagService;
import com.wisesoft.ai.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody @Valid ChatRequest request) {
        // 参数校验失败由全局异常处理返回 400（@NotBlank 空问题不再走 SSE error 事件）
        String question = request.getQuestion().trim();

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionService.createSession();
        }

        SseEmitter emitter = new SseEmitter(300000L);
        ragService.chat(sessionId, question, emitter);
        return emitter;
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

    @DeleteMapping("/session/{sessionId}")
    public ResultJson clearSession(@PathVariable String sessionId) {
        sessionService.clearSession(sessionId);
        return ResultJson.ok("会话已清除");
    }

    @PostMapping("/session/new")
    public ResultJson newSession() {
        return ResultJson.ok(Map.of("sessionId", sessionService.createSession()));
    }
}

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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "智能问答", description = "SSE 流式问答、会话管理、知识块详情")
public class ChatController {

    private final RagService ragService;
    private final SessionService sessionService;
    private final ImageUrlSigner imageUrlSigner;
    private final AiKnowledgeMapper knowledgeMapper;

    @Operation(summary = "SSE 流式问答",
            description = "发送问题（可含图片），通过 SSE 流式返回 AI 回答。事件类型：token（文本增量）、image（图片 URL 列表）、done（引用来源/相关推荐/消息ID）、error（错误信息）")
    @ApiResponse(responseCode = "200", description = "SSE 流式响应",
            content = @Content(mediaType = "text/event-stream"))
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody @Valid ChatRequest request) {
        String question = request.getQuestion().trim();

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionService.createSession();
        }

        SseEmitter emitter = new SseEmitter(300000L);
        ragService.chat(sessionId, question, request.getImages(), emitter);
        return emitter;
    }

    @Operation(summary = "会话列表", description = "列出所有会话（按更新时间倒序）")
    @GetMapping("/sessions")
    public ResultJson listSessions() {
        List<SessionInfo> sessions = sessionService.listSessions();
        return ResultJson.ok(sessions);
    }

    @Operation(summary = "会话历史", description = "获取指定会话的完整对话历史（含图片与引用来源）")
    @GetMapping("/session/{sessionId}")
    public ResultJson getHistory(
            @Parameter(description = "会话 ID") @PathVariable String sessionId) {
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

    @Operation(summary = "删除会话", description = "删除指定会话（MySQL 软删除 + Redis 清理）")
    @DeleteMapping("/session/{sessionId}")
    public ResultJson deleteSession(
            @Parameter(description = "会话 ID") @PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResultJson.ok("会话已删除");
    }

    @Operation(summary = "清空所有会话", description = "清空全部会话数据")
    @DeleteMapping("/sessions")
    public ResultJson clearAllSessions() {
        sessionService.clearAll();
        return ResultJson.ok("所有会话已清空");
    }

    @Operation(summary = "新建会话", description = "创建一个新的对话会话，返回会话 ID")
    @PostMapping("/session/new")
    public ResultJson newSession() {
        return ResultJson.ok(Map.of("sessionId", sessionService.createSession()));
    }

    @Operation(summary = "知识块详情", description = "获取指定知识块的全文内容（引用溯源：弹窗展示来源知识块全文与图片）")
    @GetMapping("/knowledge/{knowledgeId}")
    public ResultJson knowledgeDetail(
            @Parameter(description = "知识块 ID") @PathVariable String knowledgeId) {
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
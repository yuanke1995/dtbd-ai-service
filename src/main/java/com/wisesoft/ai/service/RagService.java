package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

/**
 * RAG 问答服务
 * 使用 Spring AI 的 ChatClient + QuestionAnswerAdvisor 实现检索增强生成
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final SessionService sessionService;
    private final AiAppProperties properties;

    public RagService(ChatClient chatClient,
                      VectorStore vectorStore,
                      SessionService sessionService,
                      AiAppProperties properties) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.sessionService = sessionService;
        this.properties = properties;
    }

    /**
     * 处理用户问题，通过 SSE 流式返回
     */
    public void chat(String sessionId, String question, SseEmitter emitter) {
        try {
            // 构建检索请求
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(question)
                    .topK(properties.getRetrieval().getTopK())
                    .similarityThreshold(properties.getRetrieval().getSimilarityThreshold())
                    .build();

            // 构建 ChatClient 请求（QuestionAnswerAdvisor 自动检索 + 注入上下文）
            StringBuilder fullResponse = new StringBuilder();

            chatClient.prompt()
                    .user(question)
                    .advisors(new QuestionAnswerAdvisor(vectorStore, searchRequest))
                    .advisors(a -> {
                        // 注入对话历史（最近 5 轮）
                        List<Map<String, String>> history = sessionService.getRecentHistory(sessionId, 5);
                        if (!history.isEmpty()) {
                            StringBuilder historyText = new StringBuilder("\n\n对话历史：\n");
                            for (Map<String, String> msg : history) {
                                historyText.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
                            }
                            a.param("chat_history", historyText.toString());
                        }
                    })
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullResponse.append(token);
                        sendSseEvent(emitter, "token", token, sessionId);
                    })
                    .doOnError(error -> {
                        log.error("Stream error: {}", error.getMessage());
                        sendSseEvent(emitter, "error", "AI 回复失败: " + error.getMessage(), sessionId);
                        completeEmitter(emitter);
                    })
                    .doOnComplete(() -> {
                        // 记录对话历史
                        sessionService.appendMessage(sessionId, "user", question);
                        sessionService.appendMessage(sessionId, "assistant", fullResponse.toString());
                        sendSseEvent(emitter, "done", "", sessionId);
                        completeEmitter(emitter);
                    })
                    .blockLast();

        } catch (Exception e) {
            log.error("Chat error", e);
            sendSseEvent(emitter, "error", "系统处理异常: " + e.getMessage(), sessionId);
            completeEmitter(emitter);
        }
    }

    private void sendSseEvent(SseEmitter emitter, String type, String content, String sessionId) {
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data("{\"type\":\"" + type + "\",\"content\":" +
                            com.alibaba.fastjson2.JSON.toJSONString(content) +
                            ",\"sessionId\":\"" + sessionId + "\"}"));
        } catch (IOException e) {
            // 客户端断开，忽略
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // 忽略
        }
    }
}
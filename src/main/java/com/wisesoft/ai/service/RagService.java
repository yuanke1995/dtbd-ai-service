package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiKnowledge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.*;

/**
 * RAG 问答服务
 * 手动检索 + 构建上下文（含 [图片N] 位置标记），让 LLM 在回答对应位置输出图片标记，
 * 前端将标记替换为文档原图，实现"图片出现在正确段落位置"而非末尾拼接
 * 流式采用 subscribe 异步订阅，前端断开/超时时 dispose 实现停止生成
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
    private final AiKnowledgeMapper knowledgeMapper;
    private final ImageUrlSigner imageUrlSigner;

    public RagService(ChatClient.Builder chatClientBuilder,
                      VectorStore vectorStore,
                      SessionService sessionService,
                      AiAppProperties properties,
                      AiKnowledgeMapper knowledgeMapper,
                      ImageUrlSigner imageUrlSigner) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.sessionService = sessionService;
        this.properties = properties;
        this.knowledgeMapper = knowledgeMapper;
        this.imageUrlSigner = imageUrlSigner;
    }

    /**
     * 处理用户问题，通过 SSE 流式返回
     */
    public void chat(String sessionId, String question, SseEmitter emitter) {
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(question)
                    .topK(properties.getRetrieval().getTopK())
                    .similarityThreshold(properties.getRetrieval().getSimilarityThreshold())
                    .build();

            // 1. 检索命中知识块
            List<Document> hits = vectorStore.similaritySearch(searchRequest);

            // 2. 为命中块的图片编号，构建参考资料上下文（图片标记 [图片N] 紧跟所属内容）
            Map<Integer, String> imgIndex = new LinkedHashMap<>(); // 编号(1起) -> 图片原始URL（签名在响应时动态生成，避免存库过期）
            StringBuilder context = new StringBuilder();
            int docNo = 1;
            for (Document doc : hits) {
                context.append("[").append(docNo++).append("] ").append(doc.getText()).append("\n");
                List<String> urls = imagesOf(doc);
                for (String url : urls) {
                    int seq = imgIndex.size() + 1;
                    imgIndex.put(seq, url);
                    context.append("[图片").append(seq).append("]\n");
                }
            }

            // 3. SSE 先发图片 URL 列表（编号顺序，生产开启鉴权时动态签名）
            if (!imgIndex.isEmpty()) {
                List<String> signedUrls = imgIndex.values().stream().map(imageUrlSigner::signUrl).toList();
                sendSseEvent(emitter, "image", JSON.toJSONString(signedUrls), sessionId);
            }

            // 4. System 提示：角色 + 图片标记规则 + 对话历史
            StringBuilder system = new StringBuilder(
                    "你是\"小报\"，一个基于操作手册知识库回答系统使用问题的AI助手。"
                            + "回答应准确、简洁，优先依据参考资料。"
                            + "参考资料中用 [图片N] 表示文档截图，回答时在描述对应内容的准确位置输出 [图片N]（例如\"操作步骤如图所示[图片1]\"），"
                            + "不要把图片标记堆到回答结尾，也不要编造不存在的编号。");
            List<Map<String, Object>> history = sessionService.getRecentHistory(sessionId, 5);
            if (!history.isEmpty()) {
                system.append("\n\n对话历史：\n");
                for (Map<String, Object> msg : history) {
                    system.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
                }
            }

            String user = context.length() == 0
                    ? question
                    : question + "\n\n参考资料：\n" + context;

            // 5. 异步流式生成（subscribe 释放 Servlet 线程，支持停止生成）
            StringBuilder fullResponse = new StringBuilder();
            Disposable disposable = chatClient.prompt()
                    .system(system.toString())
                    .user(user)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullResponse.append(token);
                        sendSseEvent(emitter, "token", token, sessionId);
                    })
                    .doOnError(error -> {
                        log.error("Stream error: {}", error.getMessage());
                        sendSseEvent(emitter, "error", "AI 回复失败，请稍后重试", sessionId);
                        completeEmitter(emitter);
                    })
                    .doOnComplete(() -> {
                        // 记录对话历史（存原始图片URL，恢复时由接口动态签名，避免过期）
                        sessionService.appendMessage(sessionId, "user", question, null);
                        sessionService.appendMessage(sessionId, "assistant", fullResponse.toString(),
                                new ArrayList<>(imgIndex.values()));
                        sendSseEvent(emitter, "done", "", sessionId);
                        completeEmitter(emitter);
                    })
                    .subscribe();

            // 前端断开/超时时停止生成
            emitter.onCompletion(() -> disposable.dispose());
            emitter.onTimeout(() -> disposable.dispose());
            emitter.onError(t -> disposable.dispose());

        } catch (Exception e) {
            log.error("Chat error", e);
            sendSseEvent(emitter, "error", "系统处理异常，请稍后重试", sessionId);
            completeEmitter(emitter);
        }
    }

    /**
     * 获取知识块的图片 URL：优先 Redis metadata，缺失时按 doc.getId()（=knowledgeId）查 MySQL 兜底
     */
    private List<String> imagesOf(Document doc) {
        List<String> urls = parseImages(doc.getMetadata().get("images"));
        if (urls.isEmpty()) {
            try {
                AiKnowledge k = knowledgeMapper.selectById(String.valueOf(doc.getId()));
                if (k != null && k.getImages() != null && !k.getImages().isBlank()) {
                    urls = parseImages(k.getImages());
                }
            } catch (Exception e) {
                log.warn("查询知识块图片失败: {}", e.getMessage());
            }
        }
        return urls;
    }

    /**
     * 解析图片 URL 列表，兼容 List、JSON 数组字符串两种格式
     */
    private List<String> parseImages(Object v) {
        List<String> result = new ArrayList<>();
        if (v == null) return result;
        try {
            if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null && !String.valueOf(o).isBlank()) result.add(String.valueOf(o));
                }
            } else if (v instanceof CharSequence s && !s.toString().isBlank()) {
                String str = s.toString().trim();
                if (str.startsWith("[")) {
                    result.addAll(JSON.parseArray(str, String.class));
                } else {
                    result.add(str);
                }
            }
        } catch (Exception e) {
            log.warn("解析图片列表失败: {}", e.getMessage());
        }
        return result;
    }

    private void sendSseEvent(SseEmitter emitter, String type, String content, String sessionId) {
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data("{\"type\":\"" + type + "\",\"content\":" +
                            JSON.toJSONString(content) +
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

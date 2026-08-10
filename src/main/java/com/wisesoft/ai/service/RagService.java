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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
            // 0. 查询改写（优化检索精准度；默认关闭，失败静默降级为原始问题）
            String retrievalQuery = rewriteQuery(question);

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(retrievalQuery)
                    .topK(properties.getRetrieval().getTopK())
                    .similarityThreshold(properties.getRetrieval().getSimilarityThreshold())
                    .build();

            // 1. 检索命中知识块
            List<Document> hits = vectorStore.similaritySearch(searchRequest);

            // 2. 为命中块的图片编号，构建参考资料上下文
            //    知识块正文中已包含 [图片] 或 [图片：描述]，需替换为编号 [图片N]
            //    避免 LLM 看到两套标记（无编号 + 有编号）导致回答中输出无编号的 [图片]
            Map<Integer, String> imgIndex = new LinkedHashMap<>();
            java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile("\\[图片(：.*?)?\\]");
            StringBuilder context = new StringBuilder();
            int docNo = 1;
            for (Document doc : hits) {
                String text = doc.getText();
                List<String> urls = imagesOf(doc);

                log.info("[RAG] chunk docId={} urls={} text有图片标记={}",
                        doc.getId(), urls, imgPattern.matcher(text).find());

                // 将正文中的 [图片] / [图片：xxx] 替换为全局编号 [图片N]
                int imgIdxForChunk = 0;
                java.util.regex.Matcher matcher = imgPattern.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    if (imgIdxForChunk < urls.size()) {
                        int globalSeq = imgIndex.size() + 1;
                        imgIndex.put(globalSeq, urls.get(imgIdxForChunk));
                        matcher.appendReplacement(sb, "[图片" + globalSeq + "]");
                        imgIdxForChunk++;
                    } else {
                        matcher.appendReplacement(sb, matcher.group());
                    }
                }
                matcher.appendTail(sb);
                text = sb.toString();

                context.append("[").append(docNo++).append("] ").append(text).append("\n");

                // 如果正文中的 [图片] 数量少于 metadata 中的 URL 数量，剩余的补在末尾
                for (int i = imgIdxForChunk; i < urls.size(); i++) {
                    int globalSeq = imgIndex.size() + 1;
                    imgIndex.put(globalSeq, urls.get(i));
                    context.append("[图片").append(globalSeq).append("]\n");
                }
            }

            // 3. SSE 先发图片 URL 列表（编号顺序，生产开启鉴权时动态签名）
            if (!imgIndex.isEmpty()) {
                List<String> signedUrls = imgIndex.values().stream().map(imageUrlSigner::signUrl).toList();
                log.info("[SSE] 发送 image 事件: imgIndex.size={}, signedUrls={}", imgIndex.size(), signedUrls);
                sendSseEvent(emitter, "image", JSON.toJSONString(signedUrls), sessionId);
            } else {
                log.info("[SSE] 无图片，跳过 image 事件");
            }

            // 4. System 提示：角色 + 图片标记规则 + 对话历史
            StringBuilder system = new StringBuilder(
                    "你是\"小报\"，一个基于操作手册知识库回答系统使用问题的AI助手。"
                            + "回答应准确、简洁，优先依据参考资料。"
                            + "参考资料中用 [图片N] 表示文档截图，回答时在描述对应内容的准确位置输出 [图片N]（例如\"如图[图片1]所示，架构分为两层\"），"
                            + "不要把图片标记堆到回答结尾，也不要编造不存在的编号。"
                            + "注意：标点符号放在 [图片N] 前面，如\"如图：[图片1]\"，不要写成\"如图[图片1]：\"。");
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
        Object metaImages = doc.getMetadata().get("images");
        List<String> urls = parseImages(metaImages);
        String docId = String.valueOf(doc.getId());
        if (urls.isEmpty()) {
            try {
                AiKnowledge k = knowledgeMapper.selectById(docId);
                if (k == null) {
                    log.warn("[imagesOf] MySQL 未找到 knowledge: id={}", docId);
                } else if (k.getImages() == null || k.getImages().isBlank()) {
                    log.warn("[imagesOf] MySQL knowledge.images 为空: id={}", docId);
                } else {
                    urls = parseImages(k.getImages());
                    log.info("[imagesOf] MySQL 兜底成功: id={} urls={}", docId, urls);
                }
            } catch (Exception e) {
                log.warn("[imagesOf] MySQL 查询异常: id={} error={}", docId, e.getMessage());
            }
        } else {
            log.info("[imagesOf] metadata 命中: id={} urls={}", docId, urls);
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

    /**
     * LLM 改写用户问题，优化检索精准度。
     * 失败/超时/空结果时静默降级为原始问题。
     */
    private String rewriteQuery(String question) {
        if (!properties.getQueryRewrite().isEnabled()) {
            return question;
        }
        try {
            String rewritten = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .system(properties.getQueryRewrite().getPrompt())
                            .user(question)
                            .call()
                            .content())
                    .get(properties.getQueryRewrite().getTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (rewritten == null || rewritten.isBlank()) {
                log.debug("[rewrite] LLM 返回空，降级为原始问题: {}", question);
                return question;
            }
            String trimmed = rewritten.trim();
            log.info("[rewrite] {} -> {}", question, trimmed);
            return trimmed;
        } catch (Exception e) {
            log.debug("[rewrite] 改写失败，降级为原始问题: {} error={}", question, e.getMessage());
            return question;
        }
    }
}

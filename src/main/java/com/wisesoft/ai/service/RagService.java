package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 问答服务
 * 混合检索（向量+关键词）→ 重排 → 构建上下文（含 [图片N] 位置标记 + 引用来源）
 * → LLM 流式回答，SSE 输出 token/图片/done(含引用与相关推荐)
 * 流式采用 subscribe 异步订阅，前端断开/超时时 dispose 实现停止生成
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RagService {

    /** 参与回答构建的最大命中块数（上下文长度控制） */
    private static final int MAX_CONTEXT_HITS = 8;
    /** 重排触发下限（候选太少无需重排） */
    private static final int RERANK_MIN = 6;
    private static final int RERANK_MAX = 15;
    /** 引用摘要截断长度 */
    private static final int SNIPPET_LEN = 80;

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final AiAppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    private final QaLogService qaLogService;

    public RagService(ChatClient.Builder chatClientBuilder,
                      SessionService sessionService,
                      AiAppProperties properties,
                      ImageUrlSigner imageUrlSigner,
                      HybridRetrievalService hybridRetrievalService,
                      RerankService rerankService,
                      DocumentMetaCache documentMetaCache,
                      QaLogService qaLogService) {
        this.chatClient = chatClientBuilder.build();
        this.sessionService = sessionService;
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.documentMetaCache = documentMetaCache;
        this.qaLogService = qaLogService;
    }

    /**
     * 处理用户问题，通过 SSE 流式返回
     */
    public void chat(String sessionId, String question, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        try {
            // 0. 查询改写（优化检索精准度；默认关闭，失败静默降级为原始问题）
            String retrievalQuery = rewriteQuery(question);

            // 1. 混合检索（向量 + 关键词）→ 重排
            List<HybridRetrievalService.Hit> hits = hybridRetrievalService.search(retrievalQuery);
            if (hits.size() > RERANK_MIN && hits.size() <= RERANK_MAX) {
                hits = rerankService.rank(hits, retrievalQuery);
            }
            if (hits.size() > MAX_CONTEXT_HITS) {
                hits = hits.subList(0, MAX_CONTEXT_HITS);
            }
            log.info("[RAG] 检索命中 {} 块, query={}", hits.size(), retrievalQuery);

            // 2. 为命中块的图片编号，构建参考资料上下文（保留 [图片N：描述] 供 LLM 识别）
            Map<Integer, String> imgIndex = new LinkedHashMap<>();
            Pattern imgPattern = Pattern.compile("\\[图片(：.*?)?\\]");
            StringBuilder context = new StringBuilder();
            List<Map<String, Object>> sources = new ArrayList<>();
            int docNo = 1;
            for (HybridRetrievalService.Hit hit : hits) {
                String text = hit.content();
                List<String> urls = hit.images();

                // 将正文中的 [图片] / [图片：xxx] 替换为全局编号 [图片N]，保留描述
                int imgIdxForChunk = 0;
                Matcher matcher = imgPattern.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    if (imgIdxForChunk < urls.size()) {
                        int globalSeq = imgIndex.size() + 1;
                        imgIndex.put(globalSeq, urls.get(imgIdxForChunk));
                        String raw = matcher.group();
                        String desc = "";
                        int colonIdx = raw.indexOf("：");
                        if (colonIdx >= 0 && raw.length() > colonIdx + 2) {
                            desc = raw.substring(colonIdx + 1, raw.length() - 1).trim();
                        }
                        String replacement = desc.isEmpty()
                                ? "[图片" + globalSeq + "]"
                                : "[图片" + globalSeq + "：" + desc + "]";
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                        imgIdxForChunk++;
                    } else {
                        matcher.appendReplacement(sb, matcher.group());
                    }
                }
                matcher.appendTail(sb);
                text = sb.toString();

                // 引用来源（ref 与上下文编号对应，回答中 [1] 即可溯源）
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("ref", docNo);
                src.put("knowledgeId", hit.knowledgeId());
                src.put("docId", hit.docId());
                src.put("fileName", documentMetaCache.getFileName(hit.docId()));
                src.put("title", hit.title());
                src.put("snippet", snippet(hit.content()));
                sources.add(src);

                context.append("[").append(docNo++).append("] ").append(text).append("\n");

                // 图片数量多于正文占位时，剩余补在末尾
                for (int i = imgIdxForChunk; i < urls.size(); i++) {
                    int globalSeq = imgIndex.size() + 1;
                    imgIndex.put(globalSeq, urls.get(i));
                    context.append("[图片").append(globalSeq).append("]\n");
                }
            }

            // 3. SSE 先发图片 URL 列表（编号顺序，生产开启鉴权时动态签名）
            if (!imgIndex.isEmpty()) {
                List<String> signedUrls = imgIndex.values().stream().map(imageUrlSigner::signUrl).toList();
                sendSseEvent(emitter, "image", JSON.toJSONString(signedUrls), sessionId);
            }

            // 4. System 提示：角色 + 图片标记规则 + 引用标注 + 相关推荐 + 对话历史
            StringBuilder system = new StringBuilder(
                    "你是\"小报\"，一个基于操作手册知识库回答系统使用问题的AI助手。"
                            + "回答应准确、简洁，优先依据参考资料。"
                            + "参考资料中以 [1][2] 编号标注来源，回答引用了某个资料时，在对应句末用 [N] 标注（如\"评分组件支持自定义总分[1]\"）。"
                            + "参考资料中用 [图片N] 表示文档截图，回答时在描述对应内容的准确位置输出 [图片N]（例如\"如图[图片1]所示，架构分为两层\"），"
                            + "不要把图片标记堆到回答结尾，也不要编造不存在的编号。"
                            + "注意：插入 [图片N] 时，标记前后不要紧贴任何标点，[图片N] 应独立成行；"
                            + "若句末需要标点，放在标记之前的文字末尾，如\"布局组件[图片1]\"，不要写成\"布局组件[图片1]、\"。"
                            + "回答末尾用 <related>问题1|问题2|问题3</related> 输出 3 个用户可能追问的相关问题（用 | 分隔），如无合适问题可不输出。");
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

            // 5. 异步流式生成
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
                        // 剥离 <related> 块，得到推荐问题与最终回答
                        List<String> related = extractRelated(fullResponse);
                        String answer = fullResponse.toString();

                        // 记录对话历史（含图片与引用来源），拿到消息ID供前端反馈
                        String sourcesJson = sources.isEmpty() ? null : JSON.toJSONString(sources);
                        sessionService.appendMessage(sessionId, "user", question, null, null);
                        String messageId = sessionService.appendMessage(sessionId, "assistant", answer,
                                new ArrayList<>(imgIndex.values()), sourcesJson);

                        // 异步落问答日志（不阻塞 SSE 完成）
                        List<String> hitDocIds = sources.stream().map(s -> String.valueOf(s.get("docId"))).toList();
                        qaLogService.logAsync(sessionId, question, answer, hitDocIds,
                                !sources.isEmpty(), System.currentTimeMillis() - startTime);

                        // done 事件携带引用来源/相关推荐/消息ID（反馈关联）
                        Map<String, Object> donePayload = new LinkedHashMap<>();
                        donePayload.put("sources", sources);
                        donePayload.put("related", related);
                        donePayload.put("messageId", messageId);
                        sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), sessionId);
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
     * 从回答中提取并剥离 <related> 块，返回推荐问题列表
     */
    private List<String> extractRelated(StringBuilder sb) {
        List<String> related = new ArrayList<>();
        String answer = sb.toString();
        Matcher m2 = Pattern.compile("<related>([\\s\\S]*?)</related>").matcher(answer);
        if (m2.find()) {
            String block = m2.group(1).trim();
            for (String q : block.split("[|\n]")) {
                String t = q.trim();
                if (!t.isEmpty()) related.add(t);
            }
            sb.setLength(0);
            sb.append(answer.substring(0, m2.start())).append(answer.substring(m2.end()));
        }
        return related;
    }

    private String snippet(String content) {
        if (content == null) return "";
        String s = content.replaceAll("\\[图片[^\\]]*\\]", " ").trim();
        return s.length() > SNIPPET_LEN ? s.substring(0, SNIPPET_LEN) + "…" : s;
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
     * LLM 改写用户问题，优化检索精准度。失败/超时/空结果时静默降级为原始问题。
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
                return question;
            }
            String trimmed = rewritten.trim();
            log.info("[rewrite] {} -> {}", question, trimmed);
            return trimmed;
        } catch (Exception e) {
            log.debug("[rewrite] 改写失败，降级为原始问题: {}", e.getMessage());
            return question;
        }
    }
}

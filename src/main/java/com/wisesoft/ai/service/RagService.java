package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private static final Pattern relatedPattern = Pattern.compile("<related>([\\s\\S]*?)</related>");

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final AiAppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    private final QaLogService qaLogService;
    private final UserImageService userImageService;
    private final ConfigService configService;
    private final ImageFilterService imageFilterService;

    /** M1：查询改写专用线程池（隔离超时任务，避免占用公共池/无限堆积） */
    private final ExecutorService rewriteExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "rewrite");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.PreDestroy
    void shutdownRewriteExecutor() {
        rewriteExecutor.shutdownNow();
    }

    public RagService(ChatClient.Builder chatClientBuilder,
                      SessionService sessionService,
                      AiAppProperties properties,
                      ImageUrlSigner imageUrlSigner,
                      HybridRetrievalService hybridRetrievalService,
                      RerankService rerankService,
                      DocumentMetaCache documentMetaCache,
                      QaLogService qaLogService,
                      UserImageService userImageService,
                      ConfigService configService,
                      ImageFilterService imageFilterService) {
        this.chatClient = chatClientBuilder.build();
        this.sessionService = sessionService;
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.documentMetaCache = documentMetaCache;
        this.qaLogService = qaLogService;
        this.userImageService = userImageService;
        this.configService = configService;
        this.imageFilterService = imageFilterService;
    }

    /**
     * 处理用户问题（可含上传图片），通过 SSE 流式返回
     */
    public void chat(String sessionId, String question, List<String> userImages, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        try {
            // 0. 用户上传图片：并行保存+视觉描述（用于上下文与检索召回）
            List<UserImageService.UserImage> userImgs = userImageService.process(userImages);
            String imgDescText = userImgs.isEmpty() ? "" : userImgs.stream()
                    .map(i -> "- " + (i.desc().isBlank() ? "（图片内容无法识别）" : i.desc()))
                    .collect(Collectors.joining("\n"));

            // 0. 查询改写（支持多轮历史上下文；失败静默降级为原始问题；M2：关闭时跳过历史查询）
            String retrievalQuery = question;
            if (properties.getQueryRewrite().isEnabled()) {
                List<Map<String, Object>> recentHistory = sessionService.getRecentHistory(sessionId, properties.getQueryRewrite().getHistoryRounds());
                retrievalQuery = rewriteQuery(question, recentHistory);
            }
            // 图片描述参与检索：识别界面时描述含组件名，能显著提升召回
            if (!userImgs.isEmpty()) {
                String descJoin = userImgs.stream().map(UserImageService.UserImage::desc)
                        .filter(d -> !d.isBlank()).collect(Collectors.joining(" "));
                if (!descJoin.isBlank()) {
                    retrievalQuery = question + " " + descJoin;
                }
            }

            // 日志记录用（final 副本，lambda 中引用需要 effectively final）
            final String queryForLog = retrievalQuery;

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
            // 全局图片编号 → 描述（图片相关性校验用：LLM 输出标记后逐图比对）
            Map<Integer, String> imgDescIndex = new HashMap<>();
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
                        imgDescIndex.put(globalSeq, desc);
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
                src.put("images", hit.images()); // 关联文档截图（原始URL，前端经 /proxy 访问）
                sources.add(src);

                context.append("[").append(docNo++).append("] ").append(text).append("\n");

                // 图片数量多于正文占位时，剩余补在末尾
                for (int i = imgIdxForChunk; i < urls.size(); i++) {
                    int globalSeq = imgIndex.size() + 1;
                    imgIndex.put(globalSeq, urls.get(i));
                    imgDescIndex.put(globalSeq, "");
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
                            + "参考资料中图片标记格式为 [图片N：图片内容描述]（冒号后是这张截图的实际内容）。"
                            + "回答需要配图时，必须严格根据描述选择与内容匹配的编号：例如回答\"评分组件\"时，只能选择描述里含有\"评分/五星/星级\"等词的 [图片N]，"
                            + "绝不能使用描述与回答内容无关的编号（如描述是下拉列表、日期、JSON 数据的图片）。"
                            + "选定后把 [图片N] 输出在描述对应内容的准确位置（例如\"如图[图片8]所示，评分组件支持自定义总分\"），"
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

            // 用户上传图片描述拼入问题（主 LLM 结合图片内容回答）
            StringBuilder userQuestion = new StringBuilder(question);
            if (!imgDescText.isBlank()) {
                userQuestion.append("\n\n用户上传了图片，图片内容描述如下（请结合图片内容回答问题）：\n").append(imgDescText);
            }
            String user = context.length() == 0
                    ? userQuestion.toString()
                    : userQuestion + "\n\n参考资料：\n" + context;

            // 5. 异步流式生成（缓冲过滤 <related> 块：跨 token 分割也能正确剥离，前端不会看到标签原文）
            StringBuilder fullResponse = new StringBuilder();
            StringBuilder relatedBlock = new StringBuilder();
            StringBuilder emitBuf = new StringBuilder();   // 未发送缓冲（含尾部 19 字符滑动窗口，捕获跨 token 的标签片段）
            Disposable disposable = chatClient.prompt()
                    .system(system.toString())
                    .user(user)
                    // 模型配置界面：per-request 动态覆盖模型名与温度（保存即生效）
                    .options(OpenAiChatOptions.builder()
                            .model(configService.get("chat.model"))
                            .temperature(configService.getDouble("chat.temperature"))
                            .build())
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        emitBuf.append(token);
                        String bufStr = emitBuf.toString();
                        // 存在未完整闭合的 related 块（开始/闭合标签被跨 token 切分也覆盖）：继续缓冲不下发
                        if (containsUnclosedRelated(bufStr)) {
                            if (bufStr.length() > 3000) {
                                // 异常兜底：模型未闭合标签，直接按原文发送（extractRelated 兜底清理）
                                String raw = bufStr;
                                emitBuf.setLength(0);
                                fullResponse.append(raw);
                                sendSseEvent(emitter, "token", raw, sessionId);
                            }
                            return;
                        }
                        // 剥离完整 related 块，收集推荐内容
                        java.util.regex.Matcher rm = relatedPattern.matcher(bufStr);
                        while (rm.find()) {
                            relatedBlock.append(rm.group(1)).append("\n");
                        }
                        String clean = bufStr.replaceAll("<related>[\\s\\S]*?</related>", "");
                        // 尾部保留 19 字符滑动窗口（可能是不完整标签片段），其余下发
                        emitBuf.setLength(0);
                        int keep = Math.min(19, clean.length());
                        String sendPart = clean.substring(0, clean.length() - keep);
                        String tailKeep = clean.substring(clean.length() - keep);
                        emitBuf.append(tailKeep);
                        if (!sendPart.isEmpty()) {
                            fullResponse.append(sendPart);
                            sendSseEvent(emitter, "token", sendPart, sessionId);
                        }
                    })
                    .doOnError(error -> {
                        log.error("Stream error: {}", error.getMessage());
                        sendSseEvent(emitter, "error", "AI 回复失败，请稍后重试", sessionId);
                        completeEmitter(emitter);
                    })
                    .doOnComplete(() -> {
                        // 下发缓冲尾部（可能残留滑动窗口），并剥离可能的不完整标签
                        if (emitBuf.length() > 0) {
                            String rest = emitBuf.toString().replaceAll("<related>[\\s\\S]*?</related>", "")
                                    .replaceAll("<related[\\s\\S]*$", "");
                            emitBuf.setLength(0);
                            if (!rest.isEmpty()) {
                                fullResponse.append(rest);
                                sendSseEvent(emitter, "token", rest, sessionId);
                            }
                        }
                        // 相关推荐：优先用流式收集的块内容；兜底再对完整回答剥离一次（防 </related> 缺失等异常）
                        List<String> related = parseRelatedBlock(relatedBlock);
                        if (related.isEmpty()) {
                            related = extractRelated(fullResponse);
                        }
                        String answer = fullResponse.toString();

                        // 图片相关性校验兜底：剔除与描述不匹配的 [图片N] 标记并重建编号（LLM 偶发错配）
                        List<String> finalImgs = new ArrayList<>(imgIndex.values());
                        if (properties.getImages().getImageFilter().isEnabled() && !imgIndex.isEmpty()) {
                            ImageFilterService.RebuildResult rr = imageFilterService.rebuild(answer, imgDescIndex, question,
                                    properties.getImages().getImageFilter().getMinHits(),
                                    properties.getImages().getImageFilter().getPreContextChars());
                            if (!rr.dropped().isEmpty()) {
                                log.info("[IMG-FILTER] 图片错配剔除 {} 个: {}", rr.dropped().size(), rr.dropped());
                                answer = rr.text();
                                finalImgs = rr.keptSeq().stream().map(imgIndex::get).toList();
                            }
                        }

                        // 记录对话历史（含图片与引用来源），拿到消息ID供前端反馈
                        String sourcesJson = sources.isEmpty() ? null : JSON.toJSONString(sources);
                        List<String> userImgUrls = userImgs.stream().map(UserImageService.UserImage::url).toList();
                        sessionService.appendMessage(sessionId, "user", question,
                                userImgUrls.isEmpty() ? null : userImgUrls, null);
                        String messageId = sessionService.appendMessage(sessionId, "assistant", answer,
                                finalImgs, sourcesJson);

                        // 异步落问答日志（不阻塞 SSE 完成）
                        List<String> hitDocIds = sources.stream().map(s -> String.valueOf(s.get("docId"))).toList();
                        qaLogService.logAsync(sessionId, question, answer, hitDocIds,
                                !sources.isEmpty(), System.currentTimeMillis() - startTime,
                                queryForLog);

                        // done 事件携带引用来源/相关推荐/消息ID（反馈关联）+ 校验修正后的内容/图片（前端覆盖，保证编号与图一致）
                        Map<String, Object> donePayload = new LinkedHashMap<>();
                        donePayload.put("sources", sources);
                        donePayload.put("related", related);
                        donePayload.put("messageId", messageId);
                        donePayload.put("finalContent", answer);
                        donePayload.put("finalImages", finalImgs);
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
     * 判断字符串中是否存在未完整闭合的 related 块（开始/闭合标签的跨 token 片段也算）
     */
    private boolean containsUnclosedRelated(String s) {
        if (s.contains("</related>")) {
            // 已有关闭标签：剔除完整块后，剩余部分若还有 related 痕迹则视为未闭合
            String rest = s.replaceAll("<related>[\\s\\S]*?</related>", "");
            return rest.contains("<related") || isRelatedStart(rest) || isRelatedEndStart(rest);
        }
        return s.contains("<related") || isRelatedStart(s) || isRelatedEndStart(s);
    }

    /**
     * 判断字符串末尾是否为 <related> 标签的部分前缀（捕获跨 token 分割的开始标签）
     */
    private boolean isRelatedStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "<related>".length() && "<related>".startsWith(tail);
    }

    /**
     * 判断字符串末尾是否为 </related> 标签的部分前缀（捕获跨 token 分割的闭合标签）
     */
    private boolean isRelatedEndStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "</related>".length() && "</related>".startsWith(tail);
    }

    /**
     * 解析流式收集的 <related> 块内容（已去标签）为推荐问题列表
     */
    private List<String> parseRelatedBlock(StringBuilder block) {
        List<String> related = new ArrayList<>();
        if (block == null || block.length() == 0) return related;
        for (String q : block.toString().split("[|\n]")) {
            String t = q.trim();
            if (!t.isEmpty()) related.add(t);
        }
        return related;
    }

    /**
     * 从回答中提取并剥离 <related> 块，返回推荐问题列表（兜底用）
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
     * LLM 改写用户问题，优化检索精准度。支持多轮对话上下文（追问场景）。
     * 失败/超时/空结果时静默降级为原始问题。
     */
    private String rewriteQuery(String question, List<Map<String, Object>> history) {
        if (!properties.getQueryRewrite().isEnabled()) {
            return question;
        }
        try {
            // 构建 system prompt：根据是否有足够历史选择单轮或多轮改写
            String systemPrompt;
            boolean hasHistory = history != null && history.size() >= 2;
            if (hasHistory) {
                String historyText = formatHistory(history);
                String template = properties.getQueryRewrite().getPromptMultiTurn();
                systemPrompt = template.replace("%s", historyText);
            } else {
                systemPrompt = properties.getQueryRewrite().getPrompt();
            }

            String rewritten = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .system(systemPrompt)
                            .user(question)
                            .options(OpenAiChatOptions.builder()
                                    .model(configService.get("chat.model"))
                                    .temperature(configService.getDouble("chat.temperature"))
                                    .build())
                            .call()
                            .content(), rewriteExecutor)
                    .get(properties.getQueryRewrite().getTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String trimmed = rewritten.trim();
            log.info("[rewrite] history={} {} -> {}", hasHistory, question, trimmed);
            return trimmed;
        } catch (Exception e) {
            log.debug("[rewrite] 改写失败，降级为原始问题: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 将对话历史格式化为 user/assistant 文本，用于多轮改写 prompt（M5：单条截断 200 字、总长 1500 字）
     */
    private String formatHistory(List<Map<String, Object>> history) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            if (content.length() > 200) {
                content = content.substring(0, 200) + "…";
            }
            if (role.equals("user")) {
                sb.append("用户：").append(content).append("\n");
            } else if (role.equals("assistant")) {
                sb.append("助手：").append(content).append("\n");
            }
            if (sb.length() > 1500) {
                break;
            }
        }
        return sb.toString();
    }
}

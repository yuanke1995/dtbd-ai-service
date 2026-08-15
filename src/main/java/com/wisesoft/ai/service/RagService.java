package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.util.TokenCounter;
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
    private final KeywordExtractor keywordExtractor;

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
                      ImageFilterService imageFilterService,
                      KeywordExtractor keywordExtractor) {
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
        this.keywordExtractor = keywordExtractor;
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
            //    候选全部保留，由上下文预算决定填充多少块（价值驱动，不固定截断）
            List<HybridRetrievalService.Hit> hits = hybridRetrievalService.search(retrievalQuery);
            if (hits.size() > RERANK_MIN && hits.size() <= RERANK_MAX) {
                hits = rerankService.rank(hits, retrievalQuery);
            }
            log.info("[RAG] 检索命中 {} 块, query={}", hits.size(), retrievalQuery);

            // 2. System 提示：角色段（DB 可编辑，保存即生效；空则用代码默认值兜底）+ 规则段（代码固定，与解析器耦合）
            //    + 对话历史（单条截断 + token 上限 + 图片标记剥离）
            String rolePart = configService.get("chat.systemPrompt");
            if (rolePart == null || rolePart.isBlank()) {
                rolePart = properties.getSystemPrompt();
            }
            StringBuilder system = new StringBuilder(rolePart)
                    .append("\n\n【规则】\n")
                    .append("参考资料中以 [1][2] 编号标注来源，回答引用了某个资料时，在对应句末用 [N] 标注（如\"评分组件支持自定义总分[1]\"）。")
                    .append("\n参考资料中图片标记格式为 [图片N：图片内容描述]（冒号后是这张截图的实际内容）。")
                    .append("\n回答需要配图时，必须严格根据描述选择与内容匹配的编号：例如回答\"评分组件\"时，只能选择描述里含有\"评分/五星/星级\"等词的 [图片N]，"
                            + "绝不能使用描述与回答内容无关的编号（如描述是下拉列表、日期、JSON 数据的图片）。")
                    .append("\n选定后把 [图片N] 输出在描述对应内容的准确位置（例如\"如图[图片8]所示，评分组件支持自定义总分\"），"
                            + "不要把图片标记堆到回答结尾，也不要编造不存在的编号。")
                    .append("\n注意：插入 [图片N] 时，标记前后不要紧贴任何标点，[图片N] 应独立成行；"
                            + "若句末需要标点，放在标记之前的文字末尾，如\"布局组件[图片1]\"，不要写成\"布局组件[图片1]、\"。")
                    .append("\n参考资料中包含表格时（以 | 分隔的 Markdown 表格），若回答涉及表格内容，请用同样的 Markdown 表格格式呈现，不要改写成一长串用竖线连起来的文字。")
                    .append("\n回答末尾用 <related>问题1|问题2|问题3</related> 输出 3 个用户可能追问的相关问题（用 | 分隔），如无合适问题可不输出。");
            String historyText = buildHistoryText(sessionService.getRecentHistory(sessionId, 5));
            if (!historyText.isEmpty()) {
                system.append("\n\n对话历史：\n").append(historyText);
            }

            // 用户上传图片描述拼入问题（主 LLM 结合图片内容回答）
            StringBuilder userQuestion = new StringBuilder(question);
            if (!imgDescText.isBlank()) {
                userQuestion.append("\n\n用户上传了图片，图片内容描述如下（请结合图片内容回答问题）：\n").append(imgDescText);
            }

            // 3. 价值驱动填充：预算 = min(窗口×系数−输出, 成本上限)；减去 system/问题固定部分后，按相关度累积填充知识块
            int budget = resolveContextBudget();
            int fixedTokens = TokenCounter.estimate(system.toString()) + TokenCounter.estimate(userQuestion.toString());
            int remainTokens = Math.max(800, budget - fixedTokens);

            Map<Integer, String> imgIndex = new LinkedHashMap<>();
            // 全局图片编号 → 描述（图片相关性校验用：LLM 输出标记后逐图比对）
            Map<Integer, String> imgDescIndex = new HashMap<>();
            Pattern imgPattern = Pattern.compile("\\[图片(：.*?)?\\]");
            StringBuilder context = new StringBuilder();
            List<Map<String, Object>> sources = new ArrayList<>();
            int docNo = 1;
            int usedTokens = 0;
            List<String> retrievalTerms = keywordExtractor.extract(retrievalQuery);
            int maxContextHits = configService.getInt("context.maxContextHits");
            int snippetWindow = configService.getInt("context.snippetWindowChars");
            for (HybridRetrievalService.Hit hit : hits) {
                if (docNo > maxContextHits) break;
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

                // 块内片段截取：按检索词元定位命中位置，取 ±窗口（省 token 保精度；未命中则整块）
                if (snippetWindow > 0) {
                    text = extractHitSnippet(text, retrievalTerms, snippetWindow);
                }

                // 预算控制：累积填充；除第一块外超预算即停止；第一块超预算则截断兜底（保证至少 1 块）
                int tokens = TokenCounter.estimate(text);
                if (docNo > 1 && usedTokens + tokens > remainTokens) {
                    log.debug("[CTX] 预算用尽停止填充: used={}/{} token, 已塞 {} 块", usedTokens, remainTokens, docNo - 1);
                    break;
                }
                if (usedTokens + tokens > remainTokens) {
                    text = truncateChars(text, Math.max(200, remainTokens - usedTokens));
                    tokens = TokenCounter.estimate(text);
                }

                // 引用来源（ref 与上下文编号对应，回答中 [1] 即可溯源）
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("ref", docNo);
                src.put("knowledgeId", hit.knowledgeId());
                src.put("docId", hit.docId());
                src.put("fileName", documentMetaCache.getFileName(hit.docId()));
                src.put("title", hit.title());
                src.put("snippet", snippet(text)); // 用截取后的片段做溯源摘要（更贴近命中内容）
                src.put("images", hit.images()); // 关联文档截图（原始URL，前端经 /proxy 访问）
                sources.add(src);

                context.append("[").append(docNo++).append("] ").append(text).append("\n");
                usedTokens += tokens;

                // 图片数量多于正文占位时，剩余补在末尾
                for (int i = imgIdxForChunk; i < urls.size(); i++) {
                    int globalSeq = imgIndex.size() + 1;
                    imgIndex.put(globalSeq, urls.get(i));
                    imgDescIndex.put(globalSeq, "");
                    context.append("[图片").append(globalSeq).append("]\n");
                }
            }
            log.info("[CTX] 上下文填充 {} 块, 总用 {} / 预算 {} token", docNo - 1, usedTokens + fixedTokens, budget);

            // 4. SSE 先发图片 URL 列表（编号顺序，生产开启鉴权时动态签名）
            if (!imgIndex.isEmpty()) {
                List<String> signedUrls = imgIndex.values().stream().map(imageUrlSigner::signUrl).toList();
                sendSseEvent(emitter, "image", JSON.toJSONString(signedUrls), sessionId);
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
                    // 模型配置界面：per-request 动态覆盖模型名与温度（保存即生效）；maxTokens 限制输出长度（防失控长文/成本）
                    .options(OpenAiChatOptions.builder()
                            .model(configService.get("chat.model"))
                            .temperature(configService.getDouble("chat.temperature"))
                            .maxTokens(configService.getInt("context.maxOutputTokens"))
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
                        Throwable root = error;
                        while (root.getCause() != null) root = root.getCause();
                        log.error("Stream error: {} -> {}", error.getClass().getSimpleName(), root.toString());
                        String msg = (root instanceof java.net.ConnectException)
                                ? "无法连接 AI 服务，请检查网络或 API 地址"
                                : "AI 回复失败，请稍后重试";
                        sendSseEvent(emitter, "error", msg, sessionId);
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

    /**
     * 计算上下文预算（token）：min(模型窗口 × 安全系数 − 预留输出, 成本软上限)
     * 模型窗口按当前 chat.model 子串匹配 model-windows 映射，未匹配用默认窗口
     * 参数走 ConfigService（DB 设置页保存即生效，yml 兜底）
     */
    private int resolveContextBudget() {
        String modelWindows = configService.get("context.modelWindows");
        int defaultWindow = configService.getInt("context.defaultWindowTokens");
        double safetyFactor = configService.getDouble("context.safetyFactor");
        int maxOutput = configService.getInt("context.maxOutputTokens");
        int costCap = configService.getInt("context.costCapTokens");

        int window = defaultWindow;
        String model = configService.get("chat.model");
        if (model != null && !model.isBlank() && modelWindows != null) {
            for (String entry : modelWindows.split(",")) {
                String[] kv = entry.trim().split("=");
                if (kv.length == 2 && model.contains(kv[0].trim())) {
                    try {
                        window = Integer.parseInt(kv[1].trim());
                        break;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        int budget = (int) (window * Math.max(0.1, Math.min(1, safetyFactor))) - maxOutput;
        if (costCap > 0 && budget > costCap) {
            budget = costCap;
        }
        return Math.max(budget, 1000); // 至少保留 1000 token
    }

    /**
     * 块内片段截取：按检索词元在文本中定位第一个命中位置，取 ±window 字符窗口。
     * 未命中（纯向量命中）返回整块。边界加省略号提示，且避免从 [图片N] 标记中间切断。
     */
    private String extractHitSnippet(String text, List<String> terms, int window) {
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) {
            return text;
        }
        int bestIdx = -1;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            int idx = text.indexOf(term);
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
            }
        }
        if (bestIdx < 0) {
            return text;
        }
        int start = Math.max(0, bestIdx - window);
        int end = Math.min(text.length(), bestIdx + window);
        // 窗口边界避免切断 [图片N] 标记：若边界落在 "[图片" 中，向前/向后对齐
        start = adjustBoundaryStart(text, start);
        end = adjustBoundaryEnd(text, end);
        // 窗口边界对齐行边界：截断点落在行中间时（长代码行/长命令），推进到整行末尾，
        // 避免把 pip install 之类的长命令拦腰切断产生 "open..." 半截内容
        if (end < text.length()) {
            int nl = text.indexOf('\n', end);
            if (nl >= 0 && nl - end <= window) {
                end = nl + 1; // 对齐到该行行尾（代价小于一个窗口则接受）
            }
        }
        // 起点对齐到行首：让截断片段从完整行开始，避免从行中间开始
        if (start > 0) {
            int ls = text.lastIndexOf('\n', start);
            if (start - ls <= window) {
                start = ls + 1;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("…");
        sb.append(text, start, end);
        if (end < text.length()) sb.append("…");
        return sb.toString();
    }

    private int adjustBoundaryStart(String text, int idx) {
        if (idx <= 0) return idx;
        // 若窗口起点落在 [图片N] 标记中间（'[' 前有标记内容），回退到标记开头
        if (text.charAt(idx) == ']' || (text.charAt(idx) == '片' && idx > 0 && text.charAt(idx - 1) == '图')) {
            int open = text.lastIndexOf('[', idx);
            int close = text.indexOf(']', idx);
            if (open >= 0 && close > open && close - open < 20) {
                return open;
            }
        }
        return idx;
    }

    private int adjustBoundaryEnd(String text, int idx) {
        if (idx >= text.length()) return idx;
        // 若窗口终点落在 [图片N] 标记中间，对齐到标记结束
        if (text.charAt(idx) == '[' || text.charAt(idx) == '图') {
            int close = text.indexOf(']', idx);
            if (close >= 0 && close - idx < 20) {
                return close + 1;
            }
        }
        return idx;
    }

    /**
     * 按字符数截断文本，尽量在最近分句符（。！？；\n）处断句
     */
    private String truncateChars(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        String cut = text.substring(0, maxChars);
        int lastBreak = Math.max(cut.lastIndexOf('\n'),
                Math.max(cut.lastIndexOf('。'),
                        Math.max(cut.lastIndexOf('！'), Math.max(cut.lastIndexOf('？'), cut.lastIndexOf('；')))));
        if (lastBreak > maxChars / 2) {
            cut = cut.substring(0, lastBreak + 1);
        }
        return cut + "…";
    }

    /**
     * 构建注入主回答的对话历史：单条截断 + 剥离 [图片N] 标记 + token 总上限
     */
    private String buildHistoryText(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        int perMsgChars = configService.getInt("context.historyPerMsgChars");
        int maxTokens = configService.getInt("context.historyMaxTokens");
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            content = stripImageMarks(content).trim();
            if (content.isEmpty()) continue;
            if (content.length() > perMsgChars) {
                content = content.substring(0, perMsgChars) + "…";
            }
            String line = role + ": " + content + "\n";
            int tokens = TokenCounter.estimate(line);
            if (total + tokens > maxTokens) {
                break; // 超总上限，丢弃更早的历史
            }
            sb.append(line);
            total += tokens;
        }
        return sb.toString();
    }

    /**
     * 剥离文本中的图片标记 [图片N] / [图片N：描述] / [图片：描述]（用于注入历史，避免编号与本轮冲突）
     */
    private String stripImageMarks(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("\\[图片\\d*(?:[：:][^\\]]*)?\\]", " ").replaceAll("\\s+", " ").trim();
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

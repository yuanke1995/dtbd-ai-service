package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.mapper.AiMessageMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.model.AiMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 检索量化评估：评估集（问题→期望知识块）→ 批量参数组对比 → recall@k/MRR/命中率
 * + 弃用过滤正确性断言 + 深度思考多路检索（normal vs multi）对比。
 * 参数覆盖走 ConfigService 线程局部 override，不写 DB 不污染当前配置。
 */
@Slf4j
@Service
public class RetrievalEvaluationService {

    // ==================== 数据模型 ====================

    public record EvalCase(String id, String question, List<String> expectedKnowledgeIds, String note) {}

    /** 评估集（staleExpected=生成时剔除的已失效期望知识块数，供判断标签质量） */
    public record EvalSet(int version, String generatedAt, List<EvalCase> cases, int staleExpected) {}

    /** 评估参数组（null 表示不覆盖该项，用当前配置） */
    public record EvalParams(String name, String mode,
                             Double vectorWeight, Double keywordWeight, Double titleBonus,
                             Double vecThreshold, Integer keywordLimit, Integer topK,
                             Integer rerankMinHits, Integer rerankMaxHits, Boolean rerankEnabled) {
        /** 转 ConfigService 线程局部 override map（null 项跳过） */
        public Map<String, String> toOverrides() {
            Map<String, String> m = new LinkedHashMap<>();
            if (vectorWeight != null) m.put("retrieval.vectorWeight", String.valueOf(vectorWeight));
            if (keywordWeight != null) m.put("retrieval.keywordWeight", String.valueOf(keywordWeight));
            if (titleBonus != null) m.put("retrieval.titleBonus", String.valueOf(titleBonus));
            if (vecThreshold != null) m.put("retrieval.vecThreshold", String.valueOf(vecThreshold));
            if (keywordLimit != null) m.put("retrieval.keywordLimit", String.valueOf(keywordLimit));
            if (topK != null) m.put("retrieval.vectorTopK", String.valueOf(topK));
            if (rerankMinHits != null) m.put("rerank.minHits", String.valueOf(rerankMinHits));
            if (rerankMaxHits != null) m.put("rerank.maxHits", String.valueOf(rerankMaxHits));
            if (rerankEnabled != null) m.put("rerank.enabled", String.valueOf(rerankEnabled));
            return m;
        }
    }

    public record HitInfo(String knowledgeId, String docId, String title, double score) {}

    public record CaseResult(String id, String question, List<String> expected,
                             List<HitInfo> hits, Map<String, Double> recallAtK, double mrr) {}

    public record GroupResult(String name, String mode, EvalParams params,
                              Map<String, Double> metrics, List<CaseResult> cases) {}

    public record EvalResult(List<GroupResult> groups, List<Integer> kList,
                             Map<String, Object> deprecatedCheck, List<String> timedOutGroups, long elapsedMs) {}

    // ==================== 依赖 ====================

    private final HybridRetrievalService retrievalService;
    private final ConfigService configService;
    private final KeywordExtractor keywordExtractor;
    private final RerankService rerankService;
    private final AiMessageMapper messageMapper;
    private final AiKnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;
    private final AiAppProperties properties;

    /** 评估执行线程池（daemon；每组参数一个任务，线程局部 override 不污染主线程） */
    private final ExecutorService evalPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "retrieval-eval");
        t.setDaemon(true);
        return t;
    });

    public RetrievalEvaluationService(HybridRetrievalService retrievalService, ConfigService configService,
                                      KeywordExtractor keywordExtractor, RerankService rerankService,
                                      AiMessageMapper messageMapper, AiKnowledgeMapper knowledgeMapper,
                                      AiDocumentMapper documentMapper, AiAppProperties properties) {
        this.retrievalService = retrievalService;
        this.configService = configService;
        this.keywordExtractor = keywordExtractor;
        this.rerankService = rerankService;
        this.messageMapper = messageMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
        this.properties = properties;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        evalPool.shutdownNow();
    }

    // ==================== 评估集 ====================

    private Path evalDir() {
        return Paths.get(properties.getImages().getDir(), "eval");
    }

    private Path evalFile() {
        return evalDir().resolve("retrieval-eval.json");
    }

    /** 从历史问答（c_ai_message.sources 含 knowledgeId）回放生成评估集 */
    public synchronized EvalSet generate(int maxCases) {
        int limit = Math.min(Math.max(1, maxCases), 500);
        // 取最近 N 条带引用的 assistant 消息（逻辑删除由 @TableLogic 自动过滤）
        List<AiMessage> assistants = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getRole, "assistant")
                .isNotNull(AiMessage::getSources)
                .ne(AiMessage::getSources, "")
                .orderByDesc(AiMessage::getCreateTime)
                .last("LIMIT " + limit));
        if (assistants.isEmpty()) return new EvalSet(1, now(), List.of(), 0);

        // 按 session 预取 user 消息，避免逐条查库
        Set<String> sessionIds = assistants.stream().map(AiMessage::getSessionId).collect(Collectors.toSet());
        List<AiMessage> userMsgs = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getRole, "user")
                .in(AiMessage::getSessionId, sessionIds)
                .orderByAsc(AiMessage::getSequence));
        Map<String, List<AiMessage>> bySession = userMsgs.stream()
                .collect(Collectors.groupingBy(AiMessage::getSessionId));

        // 批量校验 expected 知识块仍存在（文档删除/重解析后旧 ID 失效，会让 recall 永远到不了 1，生成时剔除并计数）
        Set<String> candidateIds = new HashSet<>();
        for (AiMessage a : assistants) candidateIds.addAll(extractKnowledgeIds(a.getSources()));
        Set<String> existingIds = loadExistingKnowledgeIds(candidateIds);

        List<EvalCase> cases = new ArrayList<>();
        Set<String> seenQuestions = new HashSet<>();
        int seq = 0;
        int stale = 0;
        for (AiMessage a : assistants) {
            // 该 assistant 消息之前的最近一条 user 消息
            List<AiMessage> users = bySession.getOrDefault(a.getSessionId(), List.of());
            AiMessage prevUser = null;
            for (AiMessage u : users) {
                if (u.getSequence() != null && a.getSequence() != null && u.getSequence() < a.getSequence()) {
                    prevUser = u;
                }
            }
            if (prevUser == null || prevUser.getContent() == null || prevUser.getContent().isBlank()) continue;
            String question = prevUser.getContent().trim();
            List<String> expected = extractKnowledgeIds(a.getSources());
            List<String> valid = expected.stream().filter(existingIds::contains).distinct().toList();
            stale += expected.size() - valid.size();
            if (valid.isEmpty() || !seenQuestions.add(question)) continue;
            cases.add(new EvalCase("c" + (++seq), truncate(question, 200), valid, ""));
        }
        EvalSet set = new EvalSet(1, now(), cases, stale);
        try {
            Files.createDirectories(evalDir());
            Files.writeString(evalFile(), JSON.toJSONString(set, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[Eval] 评估集写盘失败: {}", e.getMessage());
        }
        log.info("[Eval] 评估集生成 {} 条（取自 {} 条引用消息，剔除失效期望块 {} 个）", cases.size(), assistants.size(), stale);
        return set;
    }

    /** 批量查询仍存在的知识块 ID（评估集期望标签的存在性校验，分批避免 IN 列表过大） */
    private Set<String> loadExistingKnowledgeIds(Set<String> ids) {
        Set<String> existing = new HashSet<>();
        if (ids == null || ids.isEmpty()) return existing;
        List<String> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 500) {
            List<String> batch = all.subList(i, Math.min(i + 500, all.size()));
            try {
                knowledgeMapper.selectBatchIds(batch).forEach(k -> existing.add(k.getId()));
            } catch (Exception e) {
                log.warn("[Eval] 知识块存在性校验失败: {}", e.getMessage());
            }
        }
        return existing;
    }

    /** 解析 sources JSON 数组里的 knowledgeId */
    private List<String> extractKnowledgeIds(String sourcesJson) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray arr = JSON.parseArray(sourcesJson);
            if (arr == null) return ids;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o != null && o.getString("knowledgeId") != null) {
                    ids.add(o.getString("knowledgeId"));
                }
            }
        } catch (Exception e) {
            log.debug("[Eval] sources 解析失败: {}", e.getMessage());
        }
        return ids;
    }

    /** 读取评估集（文件不存在返回空集） */
    public EvalSet load() {
        try {
            if (Files.exists(evalFile())) {
                String json = Files.readString(evalFile(), StandardCharsets.UTF_8);
                JSONObject o = JSON.parseObject(json);
                if (o == null) return new EvalSet(1, "", List.of(), 0);
                List<EvalCase> cases = new ArrayList<>();
                JSONArray arr = o.getJSONArray("cases");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        cases.add(new EvalCase(
                                c.getString("id"),
                                c.getString("question"),
                                c.getJSONArray("expectedKnowledgeIds") == null ? List.of()
                                        : c.getJSONArray("expectedKnowledgeIds").toJavaList(String.class),
                                c.getString("note")));
                    }
                }
                return new EvalSet(o.getIntValue("version", 1), o.getString("generatedAt"), cases,
                        o.getIntValue("staleExpected", 0));
            }
        } catch (Exception e) {
            log.warn("[Eval] 评估集读取失败: {}", e.getMessage());
        }
        return new EvalSet(1, "", List.of(), 0);
    }

    // ==================== 运行评估 ====================

    /**
     * 批量运行评估：每组参数一个线程，线程内 putOverrides → 逐 case 检索 → finally clear。
     * mode=normal 单路检索；mode=multi 多路（确定性拆子问题）合并，模拟深度思考检索阶段。
     */
    public EvalResult run(List<EvalCase> cases, List<EvalParams> groups, List<Integer> kList) {
        long start = System.currentTimeMillis();
        List<Integer> ks = kList == null || kList.isEmpty() ? List.of(5, 10, 20) : kList.stream().distinct().sorted().toList();
        List<EvalParams> gs = groups == null || groups.isEmpty()
                ? List.of(new EvalParams("当前配置", "normal", null, null, null, null, null, null, null, null, null))
                : groups;

        // 弃用文档的知识块集合（断言它们不得出现在任何命中）
        Set<String> deprecatedIds = loadDeprecatedKnowledgeIds();
        List<String> violations = Collections.synchronizedList(new ArrayList<>());

        List<Future<GroupResult>> futures = new ArrayList<>();
        for (EvalParams g : gs) {
            futures.add(evalPool.submit(() -> {
                try {
                    configService.putOverrides(g.toOverrides());
                    return runGroup(g, cases, ks, deprecatedIds, violations);
                } finally {
                    configService.clearOverride();
                }
            }));
        }

        List<GroupResult> results = new ArrayList<>();
        List<String> timedOut = new ArrayList<>();
        // 超时按工作量估算：基础 60s + 每 case 3s 余量（multi 模式每 case 最多 3 路检索，关键词路最坏 800ms/路）
        long timeoutMs = 60_000L + cases.size() * 3_000L;
        for (int i = 0; i < futures.size(); i++) {
            Future<GroupResult> f = futures.get(i);
            String name = gs.get(i).name();
            try {
                results.add(f.get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                f.cancel(true); // 终止任务避免继续占用评估线程；任务 finally 仍会清理 ThreadLocal
                timedOut.add(name);
                log.warn("[Eval] 参数组[{}]评估超时({}ms)，已取消", name, timeoutMs);
            } catch (Exception e) {
                log.warn("[Eval] 参数组[{}]评估失败: {}", name, e.getMessage());
            }
        }

        Map<String, Object> deprecatedCheck = Map.of(
                "ok", violations.isEmpty(),
                "violations", List.copyOf(violations));
        return new EvalResult(results, ks, deprecatedCheck, timedOut, System.currentTimeMillis() - start);
    }

    private GroupResult runGroup(EvalParams params, List<EvalCase> cases, List<Integer> ks,
                                 Set<String> deprecatedIds, List<String> violations) {
        List<CaseResult> caseResults = new ArrayList<>();
        for (EvalCase c : cases) {
            List<HitInfo> hits = searchHits(c.question(), params.mode());
            // 弃用断言：命中里出现已弃用文档的知识块 → violation
            for (HitInfo h : hits) {
                if (deprecatedIds.contains(h.knowledgeId())) {
                    violations.add(c.id() + "(" + truncate(c.question(), 30) + ") 命中已弃用知识块 " + h.knowledgeId());
                }
            }
            caseResults.add(computeCase(c, hits, ks));
        }
        return new GroupResult(params.name(), params.mode(), params, computeMetrics(caseResults, ks), caseResults);
    }

    /** 执行检索（normal 单路 / multi 多路合并），可选重排 */
    private List<HitInfo> searchHits(String question, String mode) {
        List<HybridRetrievalService.Hit> merged;
        if ("multi".equalsIgnoreCase(mode)) {
            merged = multiSearch(question);
        } else {
            merged = retrievalService.search(question);
        }
        // 重排：命中数在 (minHits, maxHits] 且开启才触发（评估默认不开启，避免共享 rerank 状态干扰生产）
        boolean rerankOn = configService.getBoolean("rerank.enabled");
        if (rerankOn && merged.size() > configService.getInt("rerank.minHits", 6)
                && merged.size() <= configService.getInt("rerank.maxHits", 15)) {
            try {
                merged = rerankService.rank(merged, question);
            } catch (Exception e) {
                log.debug("[Eval] 重排失败回退: {}", e.getMessage());
            }
        }
        return merged.stream()
                .map(h -> new HitInfo(h.knowledgeId(), h.docId(), h.title(), h.score()))
                .toList();
    }

    /** 深度思考多路近似：确定性拆子问题逐 query 检索，按 knowledgeId 保留最高分合并（等价 searchMulti 语义） */
    private List<HybridRetrievalService.Hit> multiSearch(String question) {
        List<String> queries = subQueries(question);
        Map<String, HybridRetrievalService.Hit> merged = new LinkedHashMap<>();
        for (String q : queries) {
            try {
                for (HybridRetrievalService.Hit h : retrievalService.search(q)) {
                    merged.merge(h.knowledgeId(), h, (a, b) -> a.score() >= b.score() ? a : b);
                }
            } catch (Exception e) {
                log.debug("[Eval] 多路检索子路失败: {}", e.getMessage());
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(HybridRetrievalService.Hit::score).reversed())
                .toList();
    }

    /** 子问题拆分：含连接词拆段；否则主问题 + 关键词词元组合（确定性近似，不调 LLM） */
    private List<String> subQueries(String question) {
        List<String> parts = new ArrayList<>();
        for (String sep : List.of("和", "与", "、", "及", "，" , ",")) {
            if (question.contains(sep)) {
                for (String p : question.split(sep)) {
                    if (!p.isBlank()) parts.add(p.trim());
                }
                break;
            }
        }
        if (parts.isEmpty()) {
            parts.add(question);
            List<String> terms = keywordExtractor.extract(question);
            if (terms.size() >= 2) {
                parts.add(String.join(" ", terms.subList(0, Math.min(2, terms.size()))));
            }
        }
        return parts.stream().distinct().toList();
    }

    private CaseResult computeCase(EvalCase c, List<HitInfo> hits, List<Integer> ks) {
        Set<String> expected = new HashSet<>(c.expectedKnowledgeIds());
        Map<String, Double> recallAtK = new LinkedHashMap<>();
        for (int k : ks) {
            int hit = 0;
            int limit = Math.min(k, hits.size());
            for (int i = 0; i < limit; i++) {
                if (expected.contains(hits.get(i).knowledgeId())) hit++;
            }
            recallAtK.put("recall@" + k, expected.isEmpty() ? 1.0 : (double) hit / expected.size());
        }
        double mrr = 0;
        for (int i = 0; i < hits.size(); i++) {
            if (expected.contains(hits.get(i).knowledgeId())) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }
        return new CaseResult(c.id(), c.question(), c.expectedKnowledgeIds(), hits, recallAtK, mrr);
    }

    private Map<String, Double> computeMetrics(List<CaseResult> caseResults, List<Integer> ks) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (caseResults.isEmpty()) return metrics;
        for (int k : ks) {
            double avg = caseResults.stream().mapToDouble(c -> c.recallAtK().getOrDefault("recall@" + k, 0.0)).average().orElse(0);
            metrics.put("recall@" + k, round3(avg));
        }
        double avgMrr = caseResults.stream().mapToDouble(CaseResult::mrr).average().orElse(0);
        metrics.put("MRR", round3(avgMrr));
        long hitCases = caseResults.stream().filter(c -> c.mrr() > 0).count();
        metrics.put("hitRate", round3((double) hitCases / caseResults.size()));
        return metrics;
    }

    /** 已弃用文档（status=1）的知识块 ID 集合 */
    private Set<String> loadDeprecatedKnowledgeIds() {
        try {
            List<AiDocument> deprecated = documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                    .eq(AiDocument::getStatus, 1));
            if (deprecated.isEmpty()) return Set.of();
            List<String> docIds = deprecated.stream().map(AiDocument::getId).toList();
            List<AiKnowledge> ks = knowledgeMapper.selectList(new LambdaQueryWrapper<AiKnowledge>()
                    .in(AiKnowledge::getDocId, docIds));
            return ks.stream().map(AiKnowledge::getId).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[Eval] 弃用知识块加载失败: {}", e.getMessage());
            return Set.of();
        }
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}

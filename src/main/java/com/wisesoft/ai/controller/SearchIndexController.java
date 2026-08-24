package com.wisesoft.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.service.KeywordIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 关键词索引运维：引擎状态 / 全量重建 / 清空。
 * 索引为"最终一致"（写入 best-effort），stats 的 indexedCount 与 mysqlCount 对比即可发现漂移，
 * 漂移或首次切换引擎后执行 POST /reindex 全量重建。
 */
@Slf4j
@Tag(name = "关键词索引")
@RestController
@RequestMapping("/api/ai/search-index")
@RequiredArgsConstructor
public class SearchIndexController {

    private final KeywordIndexService keywordIndexService;
    private final AiKnowledgeMapper knowledgeMapper;

    /** 全量重建进行中标志（防并发重复重建） */
    private final AtomicBoolean reindexing = new AtomicBoolean(false);

    @Operation(summary = "引擎状态与索引统计（indexedCount 与 mysqlCount 对比可发现索引漂移）")
    @GetMapping("/stats")
    public ResultJson<Map<String, Object>> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", keywordIndexService.engine());
        m.put("available", keywordIndexService.isAvailable());
        m.put("unavailableReason", keywordIndexService.debugUnavailableReason());
        m.put("indexedCount", keywordIndexService.indexedCount());
        long mysqlCount;
        try {
            mysqlCount = knowledgeMapper.selectCount(new LambdaQueryWrapper<>());
        } catch (Exception e) {
            mysqlCount = -1;
        }
        m.put("mysqlCount", mysqlCount);
        return ResultJson.ok(m);
    }

    @Operation(summary = "全量重建索引（分批从 MySQL 读取 push；首次切换引擎与索引漂移后使用）")
    @PostMapping("/reindex")
    public ResultJson<String> reindex() {
        if (!keywordIndexService.enabled()) {
            return ResultJson.error("关键词引擎未启用 Meilisearch（keyword.engine=mysql），无需重建");
        }
        if (!reindexing.compareAndSet(false, true)) {
            return ResultJson.error("全量重建正在进行中，请稍后再试");
        }
        new Thread(() -> {
            try {
                runReindex();
            } finally {
                reindexing.set(false);
            }
        }, "search-index-reindex").start();
        return ResultJson.ok("全量重建已启动（后台执行，可通过 /stats 观察 indexedCount 增长）");
    }

    @Operation(summary = "清空索引全部文档")
    @DeleteMapping
    public ResultJson<String> clear() {
        keywordIndexService.clearAll();
        return ResultJson.ok("索引已清空（下次解析/重解析会增量写回；也可执行 /reindex 全量重建）");
    }

    /** 分批全量重建：按 id 升序分批（每批 1000），读一批 push 一批，避免一次性载入全部块 */
    private void runReindex() {
        log.info("[Reindex] 全量重建开始");
        long total = 0;
        try {
            String lastId = "";
            while (true) {
                List<AiKnowledge> batch = knowledgeMapper.selectList(
                        new LambdaQueryWrapper<AiKnowledge>()
                                .gt(AiKnowledge::getId, lastId) // UUID 字符串升序游标
                                .orderByAsc(AiKnowledge::getId)
                                .last("LIMIT 1000"));
                if (batch.isEmpty()) break;
                keywordIndexService.indexChunks(batch);
                lastId = batch.get(batch.size() - 1).getId();
                total += batch.size();
                log.info("[Reindex] 已重建 {} 块", total);
                if (batch.size() < 1000) break;
            }
            log.info("[Reindex] 全量重建完成，共 {} 块", total);
        } catch (Exception e) {
            log.error("[Reindex] 全量重建失败（已完成 {} 块）: {}", total, e.getMessage());
        }
    }
}

package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.AnswerCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 相似问题答案缓存运维接口（设置页：统计 + 清空）
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/answer-cache")
@RequiredArgsConstructor
@Tag(name = "答案缓存", description = "相似问题语义缓存：统计与清空")
public class AnswerCacheController {

    private final AnswerCacheService answerCacheService;

    @Operation(summary = "答案缓存统计", description = "语义缓存开关/阈值/最大条数/当前条数")
    @GetMapping
    public ResultJson stats() {
        return ResultJson.ok(answerCacheService.stats());
    }

    @Operation(summary = "清空答案缓存", description = "整体清空相似问题答案缓存（重新积累；知识库变更时也会自动清空）")
    @DeleteMapping
    public ResultJson clear() {
        answerCacheService.clearAll();
        return ResultJson.ok("答案缓存已清空");
    }
}
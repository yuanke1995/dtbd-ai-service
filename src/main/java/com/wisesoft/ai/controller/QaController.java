package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.QaLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 问答反馈与数据看板
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "反馈与看板", description = "回答反馈、数据统计看板")
public class QaController {

    private final QaLogService qaLogService;

    @Operation(summary = "提交回答反馈", description = "对 AI 回答进行 👍👎 评价，可选填写反馈文本")
    @PostMapping("/feedback")
    public ResultJson feedback(
            @Parameter(description = "{\"messageId\": \"消息ID\", \"rating\": 1|0, \"feedbackText\": \"可选反馈文本\"}")
            @RequestBody Map<String, Object> body) {
        String messageId = body.get("messageId") == null ? null : String.valueOf(body.get("messageId"));
        int rating = body.get("rating") == null ? 0 : Integer.parseInt(String.valueOf(body.get("rating")));
        String text = body.get("feedbackText") == null ? null : String.valueOf(body.get("feedbackText"));
        qaLogService.feedback(messageId, rating, text);
        return ResultJson.ok("感谢反馈");
    }

    @Operation(summary = "差评样本列表", description = "👎 样本按时间倒序（含问题/回答摘要/反馈说明/引用块），供看板反馈回流：加入评估集或补知识块")
    @GetMapping("/analytics/badcases")
    public ResultJson badCases(
            @Parameter(description = "返回上限（默认 50，最大 100）")
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        return ResultJson.ok(qaLogService.listBadCases(limit));
    }

    @Operation(summary = "看板统计", description = "获取数据看板聚合统计：问答量、满意率、引用率、无命中率、热门问题 TOP10、无命中问题 TOP10")
    @GetMapping("/analytics/summary")
    public ResultJson analytics() {
        return ResultJson.ok(qaLogService.analyticsSummary());
    }
}
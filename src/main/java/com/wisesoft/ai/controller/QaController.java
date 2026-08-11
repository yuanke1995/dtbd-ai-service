package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.QaLogService;
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
public class QaController {

    private final QaLogService qaLogService;

    /**
     * 提交回答反馈：body {"messageId":"...","rating":1,"feedbackText":"可选"}
     */
    @PostMapping("/feedback")
    public ResultJson feedback(@RequestBody Map<String, Object> body) {
        String messageId = body.get("messageId") == null ? null : String.valueOf(body.get("messageId"));
        int rating = body.get("rating") == null ? 0 : Integer.parseInt(String.valueOf(body.get("rating")));
        String text = body.get("feedbackText") == null ? null : String.valueOf(body.get("feedbackText"));
        qaLogService.feedback(messageId, rating, text);
        return ResultJson.ok("感谢反馈");
    }

    /**
     * 看板统计：热门问题 / 无命中率 / 反馈
     */
    @GetMapping("/analytics/summary")
    public ResultJson analytics() {
        return ResultJson.ok(qaLogService.analyticsSummary());
    }
}

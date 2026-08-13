package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模型配置接口（配置界面）
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/config")
@RequiredArgsConstructor
@Tag(name = "系统配置", description = "模型参数配置（保存即生效）")
public class ConfigController {

    private final ConfigService configService;

    @Operation(summary = "获取全量配置", description = "获取所有模型配置项（分组展示 + editable 标记；apiKey 脱敏显示）")
    @GetMapping
    public ResultJson getConfig() {
        return ResultJson.ok(configService.snapshot());
    }

    @Operation(summary = "保存配置", description = "保存可编辑的模型配置项（chat.model、chat.temperature、chat.systemPrompt、vision.model、vision.prompt），保存即生效无需重启")
    @PutMapping
    public ResultJson updateConfig(
            @Parameter(description = "{\"chat\": {\"model\": \"..\", \"temperature\": \"0.3\"}, \"vision\": {\"model\": \"..\", \"prompt\": \"..\"}}")
            @RequestBody Map<String, Map<String, String>> body) {
        try {
            Map<String, String> updated = configService.update(body);
            return ResultJson.ok(updated, "配置已保存并生效");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (Exception e) {
            return ResultJson.error(500, "保存失败: " + e.getMessage());
        }
    }
}
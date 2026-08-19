package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.parser.DocumentParser;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.RerankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
    private final List<DocumentParser> parsers;
    private final RerankService rerankService;

    @Operation(summary = "获取全量配置", description = "获取所有模型配置项（分组展示 + editable 标记；apiKey 脱敏显示）")
    @GetMapping
    public ResultJson getConfig() {
        return ResultJson.ok(configService.snapshot());
    }

    /**
     * 前端运行时配置（文档上传限制等）：动态获取，保持与后端配置一致（改后端配置前端自动同步）
     */
    @Operation(summary = "获取前端运行时配置", description = "文档上传大小上限/支持格式等（前端校验用，与后端业务配置一致）")
    @GetMapping("/public")
    public ResultJson publicConfig() {
        Map<String, Object> upload = new LinkedHashMap<>();
        // 业务上传上限（字节，来自 c_ai_config 的 upload.maxFileSize，保存即生效）
        long maxBytes = configService.getLong("upload.maxFileSize");
        if (maxBytes <= 0) {
            maxBytes = 200L * 1024 * 1024;
        }
        upload.put("maxFileSize", maxBytes);
        upload.put("maxFileSizeLabel", fmtSize(maxBytes));
        // 聚合所有解析器支持的扩展名（去重排序，供前端上传校验）
        TreeSet<String> exts = new TreeSet<>();
        for (DocumentParser p : parsers) {
            exts.addAll(p.supportedExts());
        }
        upload.put("allowedExts", new ArrayList<>(exts));
        return ResultJson.ok(Map.of("upload", upload));
    }

    /** 字节数 → 可读标签（如 209715200 → "200MB"） */
    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.0fMB", bytes / (1024.0 * 1024));
    }

    /** 重排服务可用性探测（设置页"启用重排"开关打开前校验；绕过缓存真实请求） */
    @Operation(summary = "探测重排服务", description = "强制探测 rerank 服务（/v1/models），返回 available 供前端开启开关前校验")
    @GetMapping("/rerank/check")
    public ResultJson checkRerank() {
        return ResultJson.ok(Map.of("available", rerankService.checkAvailable()));
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

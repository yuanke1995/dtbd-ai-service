package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ConfigService;
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
public class ConfigController {

    private final ConfigService configService;

    /** 全量配置（分组 + editable 标记；apiKey 脱敏） */
    @GetMapping
    public ResultJson getConfig() {
        return ResultJson.ok(configService.snapshot());
    }

    /**
     * 保存可编辑项：body {"chat":{"model":"..","temperature":".."},"vision":{"model":"..","prompt":".."}}
     */
    @PutMapping
    public ResultJson updateConfig(@RequestBody Map<String, Map<String, String>> body) {
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

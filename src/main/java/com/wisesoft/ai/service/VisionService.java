package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视觉模型服务（OpenAI 兼容协议，裸 HTTP 调用）
 * 用于将文档中提取的图片转换为文字描述，供向量检索命中
 *
 * @author yuanke
 */
@Slf4j
@Service
public class VisionService {

    private final AiAppProperties properties;
    private final ConfigService configService;
    private final RestClient restClient;

    public VisionService(AiAppProperties properties, ConfigService configService) {
        this.properties = properties;
        this.configService = configService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(properties.getVision().getTimeoutMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getVision().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 生成图片文字描述（使用配置的默认提示词）；任何失败返回 ""（降级，不中断主流程）
     */
    public String describe(byte[] imageBytes, String ext) {
        return describe(imageBytes, ext, configService.get("vision.prompt"));
    }

    /**
     * 生成图片文字描述（自定义提示词，如 OCR）；任何失败返回 ""（降级，不中断主流程）
     * 失败自动重试 retryCount 次（Ollama 偶发 500/超时）
     */
    public String describe(byte[] imageBytes, String ext, String prompt) {
        if (!properties.getVision().isEnabled() || imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        int retry = Math.max(0, properties.getVision().getRetryCount());
        Exception lastErr = null;
        for (int attempt = 0; attempt <= retry; attempt++) {
            try {
                String desc = callOnce(imageBytes, ext, prompt);
                if (desc != null && !desc.isBlank()) return desc;
                // 空响应：模型偶发空输出，重试一次
                if (attempt < retry) log.warn("图片描述为空，第 {} 次重试", attempt + 1);
            } catch (Exception e) {
                lastErr = e;
                if (attempt < retry) log.warn("图片描述失败(第 {} 次)，重试: {}", attempt + 1, e.getMessage());
            }
        }
        log.warn("图片描述最终失败: {}", lastErr == null ? "空响应" : lastErr.getMessage());
        return "";
    }

    private String callOnce(byte[] imageBytes, String ext, String prompt) {
        String mime = mimeOf(ext);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> body = new HashMap<>();
        // 模型配置界面：视觉模型名动态读 DB（保存即生效）
        body.put("model", configService.get("vision.model"));
        // qwen3 系列默认思考模式：关闭以提速且输出稳定（实测 max_tokens 在思考模型下会导致空输出，保持 0 不发送）
        if (!properties.getVision().isThink()) {
            body.put("think", false);
        }
        // Ollama 支持 keep_alive 保持模型常驻，避免每个文档解析都重新加载模型（云端服务不支持需配置为 0）
        int keepAlive = properties.getVision().getKeepAliveMinutes();
        if (keepAlive > 0) {
            body.put("keep_alive", keepAlive + "m");
        }
        body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:" + mime + ";base64," + base64)),
                Map.of("type", "text", "text", prompt)))));

        String resp = restClient.post()
                // 防御：base-url 已含 /v1（如 Ollama http://localhost:11434/v1）时不重复拼 /v1
                .uri(properties.getVision().getBaseUrl().endsWith("/v1")
                        ? "/chat/completions"
                        : "/v1/chat/completions")
                .header("Authorization", "Bearer " + properties.getVision().getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseContent(resp);
    }

    /**
     * 防御性解析：兼容标准 OpenAI choices 与 DashScope 原生 {"text":...} 两种格式
     */
    private String parseContent(String resp) {
        if (resp == null || resp.isBlank()) return "";
        try {
            JSONObject root = JSON.parseObject(resp);
            // 1) 标准 OpenAI：choices[0].message.content（String 或 分段数组）
            JSONArray choices = root.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
                if (msg != null) {
                    Object content = msg.get("content");
                    if (content instanceof String s) return s.trim();
                    if (content instanceof JSONArray arr) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : arr) {
                            if (o instanceof JSONObject part && "text".equals(part.getString("type"))) {
                                sb.append(part.getString("text"));
                            }
                        }
                        return sb.toString().trim();
                    }
                }
            }
            // 2) DashScope 原生：{"text": "..."} 或 {"output":{"text":"..."}}
            String text = root.getString("text");
            if (text != null && !text.isBlank()) return text.trim();
            JSONObject output = root.getJSONObject("output");
            if (output != null) {
                String t = output.getString("text");
                if (t != null) return t.trim();
            }
        } catch (Exception e) {
            log.warn("解析图片描述响应失败: {}", e.getMessage());
        }
        return "";
    }

    private String mimeOf(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }
}

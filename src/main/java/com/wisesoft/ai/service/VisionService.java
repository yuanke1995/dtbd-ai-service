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
    private final RestClient restClient;

    public VisionService(AiAppProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(properties.getVision().getTimeoutMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getVision().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 生成图片文字描述；任何失败返回 ""（降级，不中断主流程）
     */
    public String describe(byte[] imageBytes, String ext) {
        if (!properties.getVision().isEnabled() || imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        try {
            String mime = mimeOf(ext);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            Map<String, Object> body = new HashMap<>();
            body.put("model", properties.getVision().getModel());
            body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("type", "image_url", "image_url",
                            Map.of("url", "data:" + mime + ";base64," + base64)),
                    Map.of("type", "text", "text", properties.getVision().getPrompt())))));

            String resp = restClient.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + properties.getVision().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseContent(resp);
        } catch (Exception e) {
            log.warn("图片描述失败: {}", e.getMessage());
            return "";
        }
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

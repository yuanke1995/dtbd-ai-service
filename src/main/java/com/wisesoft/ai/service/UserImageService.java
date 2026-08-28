package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用户上传图片处理：data URL 保存到本地 + 视觉模型生成描述（并行）
 * 描述用于：① 拼入回答上下文（主 LLM 结合图片内容） ② 参与检索召回（结合知识库）
 *
 * @author yuanke
 */
@Slf4j
@Service
public class UserImageService {

    private final AiAppProperties properties;
    private final VisionService visionService;
    private final ExecutorService imageExecutor;

    public UserImageService(AiAppProperties properties, VisionService visionService, ConfigService configService) {
        this.properties = properties;
        this.visionService = visionService;
        // 用户图片并发处理（本地视觉模型资源有限；vision.userImageConcurrency 可调，默认 2）
        int concurrency = configService.getInt("vision.userImageConcurrency", 2);
        this.imageExecutor = Executors.newFixedThreadPool(Math.max(1, concurrency), r -> {
            Thread t = new Thread(r, "user-image");
            t.setDaemon(true);
            return t;
        });
    }

    public record UserImage(String url, String desc) {}

    /**
     * 处理多张图片（data URL），并行保存+描述；返回 URL 与描述（失败项过滤）
     */
    public List<UserImage> process(List<String> dataUrls) {
        if (dataUrls == null || dataUrls.isEmpty()) return List.of();
        List<CompletableFuture<UserImage>> futures = dataUrls.stream()
                .filter(u -> u != null && u.startsWith("data:"))
                .map(u -> CompletableFuture.supplyAsync(() -> processOne(u), imageExecutor))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
    }

    private UserImage processOne(String dataUrl) {
        try {
            // 解析 data:image/png;base64,xxx
            int comma = dataUrl.indexOf(',');
            if (comma <= 0) return null;
            String meta = dataUrl.substring(5, comma);
            String b64 = dataUrl.substring(comma + 1);
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            String ext = switch (mime) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/gif" -> "gif";
                case "image/webp" -> "webp";
                default -> "jpg";
            };
            byte[] bytes = Base64.getDecoder().decode(b64);
            if (bytes.length == 0) return null;

            String url = persist(bytes, ext);
            String desc = visionService.describe(bytes, ext);
            return new UserImage(url, desc);
        } catch (Exception e) {
            // L3 fail-loud：用户上传图片处理失败（描述生成失败会在回答 prompt 显示"无法识别"，此处升级明确告警）
            log.warn("[FAIL-LOUD] 用户图片处理失败: {}", e.getMessage());
            return null;
        }
    }

    private String persist(byte[] bytes, String ext) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "images", "chat");
        Files.createDirectories(dir);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Files.write(dir.resolve(name), bytes);
        return "/ai/images/chat/" + name;
    }
}

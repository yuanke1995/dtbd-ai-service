package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * 图片描述缓存（内容寻址，文件落盘）
 * key = sha256(model + "\n" + prompt + "\n" + imageBytes)
 * <p>
 * 用途：重解析时未变图片直接复用上次描述，不重复调视觉模型；
 * 且描述文本稳定 → 包含该图的块内容哈希稳定 → 块级 diff 才能复用（跳过重新 embedding）。
 * model 与 prompt 纳入 key：换模型/改提示词自动产生新缓存，不会串味。
 */
@Slf4j
@Component
public class ImageDescCache {

    private final AiAppProperties properties;

    public ImageDescCache(AiAppProperties properties) {
        this.properties = properties;
    }

    private Path descDir() {
        return Paths.get(properties.getImages().getDir(), "desc");
    }

    /** 计算缓存 key（model+prompt+图片字节 的 SHA-256）；失败返回 null（本次跳过缓存） */
    public String key(byte[] imageBytes, String prompt, String model) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String salt = (model == null ? "" : model) + "\n" + (prompt == null ? "" : prompt) + "\n";
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] d = md.digest(imageBytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.warn("[ImageDescCache] key 计算失败: {}", e.getMessage());
            return null;
        }
    }

    /** 命中返回描述；未命中/读取失败返回 null */
    public String get(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            Path f = descDir().resolve(key + ".txt");
            if (Files.exists(f)) {
                String s = Files.readString(f, StandardCharsets.UTF_8).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (IOException e) {
            log.warn("[ImageDescCache] 读取失败: {}", e.getMessage());
        }
        return null;
    }

    /** 写入缓存（失败不阻断主流程） */
    public void put(String key, String description) {
        if (key == null || key.isBlank() || description == null || description.isBlank()) return;
        try {
            Files.createDirectories(descDir());
            Files.writeString(descDir().resolve(key + ".txt"), description, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[ImageDescCache] 写入失败: {}", e.getMessage());
        }
    }
}

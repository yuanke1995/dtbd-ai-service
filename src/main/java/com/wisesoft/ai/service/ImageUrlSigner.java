package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 图片访问签名（HMAC-SHA256）
 * 生产环境（AI_IMAGES_AUTH_ENABLED=true）下图片 URL 带 expire + sig，
 * 拦截器校验签名与有效期，防止未授权枚举下载文档截图
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ImageUrlSigner {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final AiAppProperties properties;

    public ImageUrlSigner(AiAppProperties properties) {
        this.properties = properties;
    }

    /**
     * 是否启用图片鉴权
     */
    public boolean isEnabled() {
        return properties.getImages().isAuthEnabled();
    }

    /**
     * 为图片路径生成带签名与过期时间的 URL（原路径追加 ?expire=&sig=）
     * 例如 /ai/images/{docId}/0.png → /ai/images/{docId}/0.png?expire=1785...&sig=xxxx
     */
    public String signUrl(String url) {
        if (!isEnabled()) {
            return url;
        }
        long expire = Instant.now().getEpochSecond() + properties.getImages().getAuthExpireSeconds();
        String sig = sign(url, expire);
        return url + (url.contains("?") ? "&" : "?") + "expire=" + expire + "&sig=" + sig;
    }

    /**
     * 校验图片请求的签名与有效期
     *
     * @param path   请求路径（如 /images/{docId}/0.png，不含 context-path）
     * @param expire 过期时间戳（秒）
     * @param sig    签名
     */
    public boolean verify(String path, long expire, String sig) {
        if (sig == null || sig.isBlank()) {
            return false;
        }
        if (expire <= 0 || Instant.now().getEpochSecond() > expire) {
            return false;
        }
        String expected = sign(path, expire);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String data, long expire) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            mac.update(data.getBytes(StandardCharsets.UTF_8));
            mac.update(Long.toString(expire).getBytes(StandardCharsets.UTF_8));
            byte[] raw = mac.doFinal();
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("图片签名失败", e);
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private String secretKey() {
        // 签名密钥派生自 trusted-token（不额外引入配置项）
        String token = properties.getTrustedToken();
        return token == null || token.isBlank() ? "dtbd-image-signer" : token + ":image";
    }
}

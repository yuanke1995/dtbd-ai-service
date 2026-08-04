package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图片签名器单测：签名生成/校验/过期/篡改拒绝
 */
class ImageUrlSignerTest {

    private ImageUrlSigner signer;

    @BeforeEach
    void setUp() {
        AiAppProperties props = new AiAppProperties();
        props.setTrustedToken("test-token");
        props.getImages().setAuthEnabled(true);
        props.getImages().setAuthExpireSeconds(3600);
        signer = new ImageUrlSigner(props);
    }

    @Test
    void signUrl生成签名并可通过校验() {
        String url = "/ai/images/doc123/0.png";
        String signed = signer.signUrl(url);
        assertTrue(signed.startsWith(url + "?expire="));

        long expire = Long.parseLong(signed.substring(signed.indexOf("expire=") + 7, signed.indexOf("&sig=")));
        String sig = signed.substring(signed.indexOf("sig=") + 4);
        // 校验使用不含 context-path 的路径（与拦截器行为一致）
        assertTrue(signer.verify("/ai/images/doc123/0.png", expire, sig));
    }

    @Test
    void 未开启鉴权时不签名() {
        AiAppProperties props = new AiAppProperties();
        props.setTrustedToken("t");
        props.getImages().setAuthEnabled(false);
        ImageUrlSigner s = new ImageUrlSigner(props);
        assertEquals("/ai/images/a/1.png", s.signUrl("/ai/images/a/1.png"));
    }

    @Test
    void 错误签名被拒绝() {
        assertFalse(signer.verify("/ai/images/doc123/0.png", System.currentTimeMillis() / 1000 + 100, "bad-sig"));
        assertFalse(signer.verify("/ai/images/doc123/0.png", 0, null));
    }

    @Test
    void 过期签名被拒绝() {
        long past = System.currentTimeMillis() / 1000 - 10;
        String url = "/ai/images/doc123/0.png";
        String sig = signer.signUrl(url + "?expire=" + past).split("sig=")[1];
        assertFalse(signer.verify(url, past, sig));
    }
}

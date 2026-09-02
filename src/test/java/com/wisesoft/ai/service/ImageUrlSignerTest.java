package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片 URL HMAC 签名（签名/篡改/过期/开关）纯逻辑单测。
 * 生产开启图片鉴权后所有截图 URL 走此签名，校验逻辑错误会把全部图片打回 401。
 */
class ImageUrlSignerTest {

    private AiAppProperties properties;
    private ImageUrlSigner signer;

    @BeforeEach
    void setUp() {
        properties = new AiAppProperties();
        properties.setTrustedToken("unit-test-token");
        properties.getImages().setAuthEnabled(true);
        properties.getImages().setAuthExpireSeconds(3600);
        signer = new ImageUrlSigner(properties);
    }

    private record Parsed(String path, long expire, String sig) {
    }

    private Parsed parse(String signed) {
        int q = signed.indexOf('?');
        assertTrue(q > 0, "签名 URL 应带查询参数: " + signed);
        String path = signed.substring(0, q);
        String query = signed.substring(q + 1);
        long expire = 0;
        String sig = null;
        for (String kv : query.split("&")) {
            String[] pair = kv.split("=", 2);
            if ("expire".equals(pair[0])) expire = Long.parseLong(pair[1]);
            if ("sig".equals(pair[0])) sig = pair[1];
        }
        assertNotNull(sig, "应包含 sig");
        return new Parsed(path, expire, sig);
    }

    @Test
    void 签名URL含过期时间与签名() {
        Parsed p = parse(signer.signUrl("/ai/images/doc/1.png"));
        assertTrue(p.expire() > System.currentTimeMillis() / 1000, "expire 应为未来时间");
    }

    @Test
    void 正确签名校验通过() {
        String signed = signer.signUrl("/ai/images/doc/1.png?x=1");
        Parsed p = parse(signed);
        assertTrue(signer.verify(p.path(), p.expire(), p.sig()));
    }

    @Test
    void 篡改签名校验失败() {
        Parsed p = parse(signer.signUrl("/ai/images/doc/1.png"));
        assertFalse(signer.verify(p.path(), p.expire(), "deadbeef"));
        assertFalse(signer.verify(p.path() + "x", p.expire(), p.sig()), "路径篡改应失败");
        assertFalse(signer.verify("/ai/images/other/2.png", p.expire(), p.sig()), "换图（换路径）应失败");
    }

    @Test
    void 过期签名校验失败() {
        Parsed p = parse(signer.signUrl("/ai/images/doc/1.png"));
        assertFalse(signer.verify(p.path(), p.expire() - 7200, p.sig()), "已过期应拒绝");
    }

    @Test
    void 空签名与非法过期拒绝() {
        Parsed p = parse(signer.signUrl("/ai/images/doc/1.png"));
        assertFalse(signer.verify(p.path(), p.expire(), null));
        assertFalse(signer.verify(p.path(), p.expire(), ""));
        assertFalse(signer.verify(p.path(), -1, p.sig()));
    }

    @Test
    void 鉴权关闭时URL原样返回() {
        properties.getImages().setAuthEnabled(false);
        ImageUrlSigner off = new ImageUrlSigner(properties);
        String url = "/ai/images/doc/1.png?size=small";
        assertEquals(url, off.signUrl(url), "关闭鉴权不应追加签名参数");
        assertFalse(off.isEnabled());
    }
}

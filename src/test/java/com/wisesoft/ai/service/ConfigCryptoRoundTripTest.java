package com.wisesoft.ai.service;

import com.wisesoft.ai.config.AiAppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 敏感配置 RSA 密钥文件跨实例 round-trip 测试。
 * <p>
 * 回归背景：loadKeys 的 substring 长度写错（public= 7 字符却截 8），每行丢 base64 首字符，
 * 密钥文件在生成实例之外的任何重启都无法解码——已加密配置解不开（网关 401）、重启后保存又回退明文。
 * 本测试用真实临时目录验证「实例 A 生成 → 实例 B 加载」闭环。
 */
class ConfigCryptoRoundTripTest {

    @TempDir
    Path tempDir;

    private ConfigCryptoService newInstance() {
        AiAppProperties props = new AiAppProperties();
        props.getImages().setDir(tempDir.toString());
        Environment env = mock(Environment.class);
        when(env.getProperty("AI_CONFIG_RSA_KEY")).thenReturn(null); // 默认密钥路径（数据目录下）
        return new ConfigCryptoService(props, env);
    }

    @Test
    void 生成实例与重载实例可互解() {
        ConfigCryptoService first = newInstance();
        first.init(); // 实例 A：生成密钥文件 + 加载内存密钥

        // 实例 B（模拟重启）：同目录重新加载密钥文件——此前 bug 在此 decode 失败
        ConfigCryptoService second = newInstance();
        second.init();

        String plain = "sk-" + "0123456789abcdef";
        String cipher = second.encrypt(plain);
        assertTrue(cipher.startsWith("RSA:"), "应产出带前缀密文");
        assertFalse(cipher.equals(plain), "密文不得等于明文");
        assertEquals(plain, second.decrypt(cipher), "同一实例解密应还原");
        assertEquals(plain, first.decrypt(cipher), "跨实例（生成方）解密应还原——证明密钥文件加载正确");
    }

    @Test
    void 明文与已加密值原样透传() {
        ConfigCryptoService svc = newInstance();
        svc.init();
        // 兼容存量明文读取：无前缀值解密时原样返回
        assertEquals("legacy-plain", svc.decrypt("legacy-plain"));
        // encrypt 用于明文入库保护：明文应产出 RSA 密文（存量迁移/保存路径）
        assertTrue(svc.encrypt("legacy-plain").startsWith("RSA:"), "encrypt 应将明文加密");
        // 已加密值不重复加密；解密还原明文
        String cipher = svc.encrypt("sk-abc");
        assertEquals(cipher, svc.encrypt(cipher));
        assertEquals("sk-abc", svc.decrypt(cipher));
        // 空值安全
        assertEquals(null, svc.encrypt(null));
        assertEquals("", svc.decrypt(""));
    }

    @Test
    void 篡改密文返回原值不抛异常() {
        ConfigCryptoService svc = newInstance();
        svc.init();
        String cipher = svc.encrypt("sk-secret-123");
        // 密文被篡改/密钥不匹配：按传入原值返回（fail-loud 由日志承载），不抛异常不阻塞调用链
        assertEquals(cipher + "00", svc.decrypt(cipher + "00"));
    }
}

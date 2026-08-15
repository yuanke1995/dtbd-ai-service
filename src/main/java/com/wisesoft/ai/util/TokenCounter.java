package com.wisesoft.ai.util;

/**
 * Token 估算工具（无需依赖模型 tokenizer，用于上下文预算控制）
 * <p>
 * 分语言近似估算：
 * - 中文字符（CJK）：约 1 token/字（qwen/千问系列中文密度）
 * - 英文单词：约 1.3 token/词（含常见标点粘连）
 * - 数字/其他符号：约 0.3 token/字符
 * 最后整体 +10% 余量，防止估算偏低导致超窗。
 *
 * @author yuanke
 */
public final class TokenCounter {

    private TokenCounter() {
    }

    /**
     * 估算文本的 token 数（含 10% 余量）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int wordChars = 0;
        int words = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
                words += flushWord(wordChars);
                wordChars = 0;
            } else if (Character.isLetterOrDigit(c)) {
                wordChars++;
            } else {
                // 标点/空格/符号：结束当前英文单词
                words += flushWord(wordChars);
                wordChars = 0;
                other++;
            }
        }
        words += flushWord(wordChars);

        double tokens = cjk * 1.0 + words * 1.3 + other * 0.3;
        return (int) Math.ceil(tokens * 1.1); // 10% 余量
    }

    private static int flushWord(int wordChars) {
        return wordChars > 0 ? 1 : 0;
    }

    /**
     * CJK 统一表意文字（含扩展 A/B、兼容表意、标点 CJK 符号按字符计）
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)   // 扩展A
                || (c >= 0x20000 && c <= 0x2A6DF) // 扩展B（char 无法表示，占位保留）
                || (c >= 0xF900 && c <= 0xFAFF);  // 兼容表意
    }
}

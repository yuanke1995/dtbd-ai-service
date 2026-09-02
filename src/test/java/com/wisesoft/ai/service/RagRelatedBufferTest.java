package com.wisesoft.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主回答流 SSE 滑动窗口缓冲判定（related 块完整性）纯逻辑单测。
 * 流式 token 可能把 <related>...</related> 切成任意片段，判定失误会让前端看到标签原文
 * 或把完整块当未闭合永久积压——输出缓冲依赖此判定的正确性。
 */
class RagRelatedBufferTest {

    @Test
    void 无任何标签痕迹() {
        assertFalse(RagService.containsUnclosedRelated("这是正常回答内容，包含一些[1]引用。"));
    }

    @Test
    void 完整闭合块不算未闭合() {
        assertFalse(RagService.containsUnclosedRelated("回答正文<related>问题一|问题二</related>"));
        assertFalse(RagService.containsUnclosedRelated("<related>问题一|问题二</related>"));
    }

    @Test
    void 只有开始标签未闭合() {
        assertTrue(RagService.containsUnclosedRelated("正文<related>问题一"));
        assertTrue(RagService.containsUnclosedRelated("<related>"));
    }

    @Test
    void 开始标签被跨token切成片段() {
        // token 边界恰好落在 <rel 处
        assertTrue(RagService.containsUnclosedRelated("正文<rel"));
        assertTrue(RagService.containsUnclosedRelated("<re"));
        assertTrue(RagService.containsUnclosedRelated("<"));
    }

    @Test
    void 闭合标签缺失但正文已结束() {
        assertTrue(RagService.containsUnclosedRelated("正文<related>问题一|问题二</"));
        assertTrue(RagService.containsUnclosedRelated("正文<related>问题一</rela"));
    }

    @Test
    void 完整块与不完整块并存() {
        // 有完整闭合标签，剔除完整块后仍残留开始痕迹 → 未闭合（会继续缓冲）
        assertTrue(RagService.containsUnclosedRelated("<related>问题一</related>后又出现<related>未结束"));
        // 完整闭合后无残留 → 正常
        assertFalse(RagService.containsUnclosedRelated("<related>问题一</related>回答结尾"));
    }

    @Test
    void 正文误含尖括号不误判() {
        assertFalse(RagService.containsUnclosedRelated("1 < 2 且 3 > 2"));
        assertFalse(RagService.containsUnclosedRelated("使用 <br> 类似标签"));
    }
}

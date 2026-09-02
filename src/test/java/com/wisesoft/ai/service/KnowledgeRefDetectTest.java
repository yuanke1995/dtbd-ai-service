package com.wisesoft.ai.service;

import com.wisesoft.ai.service.KnowledgeRefService.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识块交叉引用识别（7 类引用表达）纯逻辑单测。
 * 引用表达式改动频繁且直接影响引用扩散链路，回归保护：显式引用/无动词提及/排除规则。
 * 不依赖 Spring：直接调 package-private 静态核心 detectRefs0（mention 开关入参化）。
 */
class KnowledgeRefDetectTest {

    private List<Candidate> detect(String content, boolean mention) {
        return KnowledgeRefService.detectRefs0(content, mention);
    }

    @Test
    void 编号引用_详见() {
        List<Candidate> refs = detect("该字段说明详见 4.1.2 节。", false);
        assertEquals(1, refs.size(), "应识别 1 条编号引用");
        Candidate c = refs.get(0);
        assertTrue(c.loose(), "显式引用为可模糊匹配");
        assertEquals("4.1.2", c.num());
        assertTrue(c.refText().contains("4.1.2"));
    }

    @Test
    void 多级编号引用() {
        List<Candidate> refs = detect("配置方法参见 3.2.1.5 小节。", false);
        assertEquals(1, refs.size());
        assertEquals("3.2.1.5", refs.get(0).num());
    }

    @Test
    void 中文数字章节_见第X章() {
        // 设计使然的双通道：REF_ZH 产出编号 5（供标题"5 xxx"形态匹配），REF_BARE 同时以"第五章"
        // 裸名产出 name 候选（供标题"第五章 xxx"形态匹配）；最终由 matchTarget 按目标标题收敛
        List<Candidate> refs = detect("详细规则见 第五章。", false);
        assertEquals(2, refs.size());
        assertEquals("5", refs.get(0).num(), "显式引用优先通道为编号（第五章 → 5）");
        assertTrue(refs.stream().anyMatch(c -> c.num() == null && "第五章".equals(c.name())),
                "应同时提供「第五章」name 通道");
    }

    @Test
    void 书名号章节名() {
        List<Candidate> refs = detect("数据结构参见《数据字典》。", false);
        assertEquals(1, refs.size());
        assertEquals("数据字典", refs.get(0).name());
    }

    @Test
    void 裸章节名() {
        List<Candidate> refs = detect("入口详见 数值Api类型。", false);
        assertEquals(1, refs.size());
        assertEquals("数值Api类型", refs.get(0).name());
    }

    @Test
    void 图与表的编号引用被排除() {
        // "见图 3.1.1 节" 与 "如表 4.1.2 节" 是图/表引用，不是章节引用；仅"详见 4.1.2 节"应命中
        List<Candidate> refs = detect("详见 4.1.2 节；对比示例见图 3.1.1 节，汇总如表 4.1.2 节。", false);
        assertEquals(1, refs.size());
        assertEquals("4.1.2", refs.get(0).num());
    }

    @Test
    void 无动词提及_默认开_可关闭() {
        String content = "该规则在 4.1.2 节中说明；另有《字段校验》部分可参考。";
        List<Candidate> on = detect(content, true);
        assertEquals(2, on.size(), "提及开启：编号提及 + 书名号提及各 1 条");
        assertTrue(on.stream().noneMatch(Candidate::loose), "提及类不可模糊匹配");
        List<Candidate> off = detect(content, false);
        assertTrue(off.isEmpty(), "提及关闭时不应产生任何引用");
    }

    @Test
    void 章节后缀提及_开关控制() {
        // "见 报表设计模块"：显式通道产出完整名「报表设计模块」，提及通道产出后缀剥离名「报表设计」——
        // 双通道分别匹配「报表设计模块」/「报表设计」两种标题形态，由 matchTarget 收敛
        List<Candidate> on = detect("相关说明见 报表设计模块。", true);
        assertEquals(2, on.size());
        assertTrue(on.stream().anyMatch(c -> "报表设计".equals(c.name())), "提及通道应剥离「模块」后缀");
        assertTrue(on.stream().anyMatch(c -> "报表设计模块".equals(c.name())), "显式通道应保留完整名");
        // 提及关闭：仅显式通道命中（完整名 1 条，无后缀剥离候选）
        List<Candidate> off = detect("相关说明见 报表设计模块。", false);
        assertEquals(1, off.size());
        assertEquals("报表设计模块", off.get(0).name());
    }

    @Test
    void 书名号纯数字被排除() {
        // 见"2024"是数值引用语义，不建章节引用；《数据字典》正常命中
        List<Candidate> refs = detect("参见《2024》与《数据字典》。", true);
        assertEquals(1, refs.size());
        assertEquals("数据字典", refs.get(0).name());
    }

    @Test
    void 空内容与空提及不产生候选() {
        assertTrue(detect(null, true).isEmpty());
        assertTrue(detect("", true).isEmpty());
        assertTrue(detect("   ", true).isEmpty());
    }

    @Test
    void 上限截断() {
        // 大量提及（如连续书名号）受 MAX_REFS_PER_BLOCK=8 保护，防 ref 膨胀
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("详见《章节").append(i).append("》。");
        }
        List<Candidate> refs = detect(sb.toString(), false);
        assertTrue(refs.size() <= 8, "引用条数应被截断在 8 以内");
        assertEquals("章节1", refs.get(0).name(), "显式引用保持先后顺序");
    }
}

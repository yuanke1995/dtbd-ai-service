package com.wisesoft.ai.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 中文查询词元提取（用于关键词召回）
 * 按标点/空白切分为词元，过滤停用词，优先长词（长词更精准），最多 N 个
 *
 * @author yuanke
 */
@Component
public class KeywordExtractor {

    /** 常见停用词（疑问词/语气词/无检索价值） */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "吗", "呢", "吧", "啊", "么", "怎么", "怎样", "如何", "什么", "哪些",
            "哪个", "为什么", "请问", "请", "帮我", "一下", "介绍", "说明", "讲讲", "告诉",
            "这个", "那个", "一个", "功能", "操作", "使用", "方法", "步骤", "流程", "设置",
            "可以", "需要", "是否", "应该", "咋", "咋样", "比如", "例如", "关于", "在", "是", "的", "和", "与");

    private static final int MAX_TERMS = 5;

    /**
     * 提取检索词元（≥2 字，优先长词），空输入返回空列表
     */
    public List<String> extract(String query) {
        if (query == null || query.isBlank()) return List.of();
        // 按标点/空白切分（保留中英文数字连续串）
        String[] parts = query.split("[\\s\\p{Punct}，。、；：！？（）【】“”‘’·…—]+");
        List<String> terms = new ArrayList<>();
        for (String part : parts) {
            String t = part.trim();
            if (t.length() < 2) continue;               // 单字无检索价值
            if (STOP_WORDS.contains(t)) continue;
            // 长词（≥4 字）拆出子串？不拆——直接保留整词，LIKE 子串天然覆盖
            if (!terms.contains(t)) terms.add(t);
        }
        // 按长度降序（长词优先），取前 MAX_TERMS
        terms.sort((a, b) -> b.length() - a.length());
        return terms.size() > MAX_TERMS ? terms.subList(0, MAX_TERMS) : terms;
    }
}

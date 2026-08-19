package com.wisesoft.ai.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 中文查询词元提取（用于关键词召回）
 * <p>
 * 两级提取：
 * 1. 按标点/空白切分为整词，过滤停用词，长词优先
 * 2. 对纯中文长词（≥3 字）补充 2-gram 分解子词元（如"创建表单" → 创建/建表/表单），
 *    提高"换说法/子串匹配"场景的召回（无需引入重型分词依赖）
 *
 * @author yuanke
 */
@Component
public class KeywordExtractor {

    private final ConfigService configService;

    public KeywordExtractor(ConfigService configService) {
        this.configService = configService;
    }

    /** 常见停用词（疑问词/语气词/无检索价值）。注意：Set.of 不允许重复元素！ */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "吗", "呢", "吧", "啊", "么", "怎么", "怎样", "如何", "什么", "哪些",
            "哪个", "为什么", "请问", "请", "帮我", "一下", "介绍", "说明", "讲讲", "告诉",
            "这个", "那个", "一个", "功能", "操作", "使用", "方法", "步骤", "流程", "设置",
            "可以", "需要", "是否", "应该", "咋", "咋样", "比如", "例如", "关于", "在", "是", "和", "与");

    /** 单字/噪声级 bigram 停用字（bigram 中含这些字时跳过，如"个新"、"么设"） */
    private static final Set<Character> STOP_CHARS = Set.of(
            '的', '了', '吗', '呢', '吧', '啊', '么', '是', '在', '和', '与', '请', '我', '你', '它', '这', '那');

    /** 原词最大数 / 原词+bigram 总词元上限（控制 LIKE OR 数量与 SQL 开销）；retrieval.keywordMax* 可调 */
    private int maxTerms() { return configService.getInt("retrieval.keywordMaxTerms", 6); }
    private int maxTotal() { return configService.getInt("retrieval.keywordMaxTotal", 12); }

    /**
     * 提取检索词元（主词元优先，子词元补充；空输入返回空列表）
     */
    public List<String> extract(String query) {
        if (query == null || query.isBlank()) return List.of();
        // 按标点/空白切分（保留中英文数字连续串）
        String[] parts = query.split("[\\s\\p{Punct}，。、；：！？（）【】“”‘’·…—]+");
        LinkedHashSet<String> mainTerms = new LinkedHashSet<>();
        LinkedHashSet<String> subTerms = new LinkedHashSet<>();
        for (String part : parts) {
            String t = part.trim();
            if (t.length() < 2) continue;               // 单字无检索价值
            if (STOP_WORDS.contains(t)) continue;
            mainTerms.add(t);
            // 补充子词元（提高"换说法/子串"场景召回，LIKE 全词匹配不到子内容）
            if (isPureChinese(t) && t.length() >= 3) {
                // 纯中文词：2-gram 子词元（"表单""创建"等有效词）
                for (int i = 0; i < t.length() - 1; i++) {
                    String g = t.substring(i, i + 2);
                    if (STOP_CHARS.contains(g.charAt(0)) || STOP_CHARS.contains(g.charAt(1))) continue;
                    subTerms.add(g);
                }
            } else if (t.length() >= 6) {
                // 中英混合长词：4 字滑窗子词元（拆出"核心优势"这类子串）
                for (int i = 0; i <= t.length() - 4; i++) {
                    String g = t.substring(i, i + 4);
                    if (STOP_CHARS.contains(g.charAt(0)) || STOP_CHARS.contains(g.charAt(1))) continue;
                    subTerms.add(g);
                }
            }
        }
        // 主词元按长度降序（长词更精准），子词元按长度降序，取前 MAX_TERMS 个主词元
        List<String> main = mainTerms.stream()
                .sorted((a, b) -> b.length() - a.length())
                .limit(maxTerms()).toList();
        List<String> sub = subTerms.stream()
                .sorted((a, b) -> b.length() - a.length())
                .limit(Math.max(0, maxTotal() - main.size())).toList();
        List<String> result = new ArrayList<>(main);
        result.addAll(sub);
        return result;
    }

    private boolean isPureChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 0x4E00 && c <= 0x9FFF)) return false;
        }
        return true;
    }
}

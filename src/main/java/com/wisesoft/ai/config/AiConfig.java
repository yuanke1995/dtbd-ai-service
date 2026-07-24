package com.wisesoft.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置
 * ChatModel、EmbeddingModel、RedisVectorStore 由 Spring AI 自动装配
 *
 * @author yuanke
 */
@Configuration
public class AiConfig {

    /**
     * 构建 ChatClient
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    private static final String SYSTEM_PROMPT =
            "你是【报表填报平台】的智能助手，名字叫\"小报\"。\n" +
            "你基于操作手册和知识库文档来回答用户关于系统如何操作、如何设计表单、如何设置字段验证等问题。\n\n" +
            "回答规则：\n" +
            "- 用中文回答，语气友好、专业\n" +
            "- 回答要简洁准确，适当使用 Markdown 格式\n" +
            "- 严格基于提供的知识库内容回答，不要编造信息\n" +
            "- 如果知识库中没有相关信息，请说\"我目前的知识库中还没有相关信息，建议查阅操作手册或联系管理员\"\n" +
            "- 如果用户问题与系统无关，礼貌地引导回系统相关问题";
}
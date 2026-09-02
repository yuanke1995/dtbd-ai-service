package com.wisesoft.ai.config;

import com.wisesoft.ai.service.DynamicOpenAiChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 跨厂商热切换的 ChatClient：底层为 {@link DynamicOpenAiChatModel}，
 * 网关地址/API Key/补全路径（c_ai_config 的 chat.baseUrl / chat.apiKey / chat.completionsPath）
 * 在设置页修改保存即生效，下一次请求自动用新网关，无需重启服务。
 * <p>
 * 替换 Spring AI 自动配置基于 yml 单例 ChatModel 的 ChatClient.Builder 注入（RagService 改为注入本 ChatClient）。
 *
 * @author yuanke
 */
@Configuration
public class DynamicChatClientConfig {

    @Bean
    public ChatClient chatClient(DynamicOpenAiChatModel dynamicOpenAiChatModel) {
        return ChatClient.builder(dynamicOpenAiChatModel).build();
    }
}

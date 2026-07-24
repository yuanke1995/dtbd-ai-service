package com.wisesoft.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 应用自定义配置
 *
 * @author yuanke
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-app")
public class AiAppProperties {

    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Session session = new Session();

    /** 内部信任 token（dtbd-core 代理调用时携带） */
    private String trustedToken = "dtbd-ai-internal-token";

    @Data
    public static class Chunk {
        /** 分块最大字符数 */
        private int maxSize = 800;
        /** 分块重叠字符数 */
        private int overlap = 100;
    }

    @Data
    public static class Retrieval {
        /** 检索返回 Top-K */
        private int topK = 5;
        /** 相似度阈值 */
        private double similarityThreshold = 0.5;
    }

    @Data
    public static class Session {
        /** 保留最近对话轮数 */
        private int maxHistory = 10;
        /** 会话过期时间（分钟） */
        private int expireMinutes = 30;
    }
}
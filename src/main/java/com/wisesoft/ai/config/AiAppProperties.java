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
    private Images images = new Images();
    private Vision vision = new Vision();

    /** 内部信任 token（dtbd-core 代理调用时携带；必须通过环境变量配置，无默认值） */
    private String trustedToken;

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

    @Data
    public static class Images {
        /** 图片存储根目录，默认 ./data（相对应用工作目录） */
        private String dir = "data";
        /** 每篇文档最多提取图片数 */
        private int maxPerDoc = 20;
        /** 图片 URL 访问前缀（含 context-path /ai） */
        private String urlPrefix = "/ai/images";
        /** 图片访问鉴权开关（HMAC 签名 URL，生产开启） */
        private boolean authEnabled = false;
        /** 签名 URL 有效期（秒） */
        private long authExpireSeconds = 3600;
    }

    @Data
    public static class Vision {
        /** 图片描述模型（全模态，已实测返回标准 OpenAI 格式；求快可换 qwen3-omni-flash） */
        private String model = "qwen3.5-omni-plus";
        /** 视觉模型 base-url（与 chat 同网关） */
        private String baseUrl = "https://llm-xdqpg8ip850vmxh3.cn-beijing.maas.aliyuncs.com/compatible-mode";
        /** 视觉模型 API Key */
        private String apiKey = "";
        /** 是否启用图片描述（关闭则只提取图片不调模型） */
        private boolean enabled = true;
        /** 单张图片描述超时(ms) */
        private int timeoutMillis = 30000;
        /** 描述 prompt */
        private String prompt = "请简要描述这张图片的内容，如果是界面截图请提取关键文字和界面元素，如果是流程图请说明流程要点，50字以内。";
    }
}
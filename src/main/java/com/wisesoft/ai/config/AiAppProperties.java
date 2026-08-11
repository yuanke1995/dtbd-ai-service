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

    private QueryRewrite queryRewrite = new QueryRewrite();

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
        /** 图片最长边像素，超过则等比缩小（0=不压缩） */
        private int maxWidth = 1280;
        /** JPEG 压缩质量（0~1） */
        private float quality = 0.8f;
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
        private String model = "";
        /** 视觉模型 base-url（与 chat 同网关） */
        private String baseUrl = "";
        /** 视觉模型 API Key */
        private String apiKey = "";
        /** 是否启用图片描述（关闭则只提取图片不调模型） */
        private boolean enabled = true;
        /** 单张图片描述超时(ms) */
        private int timeoutMillis = 30000;
        /** 图片描述并发度（本地 Ollama 需设置 OLLAMA_NUM_PARALLEL 才能并行推理） */
        private int concurrency = 4;
        /** 单张图片失败重试次数（Ollama 偶发 500/超时，重试可显著降低降级率） */
        private int retryCount = 1;
        /** Ollama keep_alive 保持模型常驻(分钟)，0=不发送（云端服务不支持此参数需设 0） */
        private int keepAliveMinutes = 30;
        /** 请求超时兜底(ms)，若单请求处理超长则不等待直接降级 */
        private int abortMillis = 0;
        /**
         * 关闭思考模式（qwen3 系列默认思考，关闭后提速且输出稳定）。
         * 注意：max_tokens 在该思考模型下会导致空输出，本项目不发送 max_tokens
         */
        private boolean think = false;
        /** 描述 prompt */
        private String prompt = "请简要描述这张图片的内容，如果是界面截图请提取关键文字和界面元素，如果是流程图请说明流程要点，50字以内。";
    }

    @Data
    public static class QueryRewrite {
        /** 是否启用查询改写 */
        private boolean enabled = false;
        /** 改写超时时间(ms) */
        private int timeoutMillis = 5000;
        /** 改写 prompt */
        private String prompt = "请将用户问题改写为一个更精准的检索关键词或短语。只需输出改写后的文本，不要解释。";
    }
}
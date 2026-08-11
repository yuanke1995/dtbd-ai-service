# DTBD AI Service

报表平台独立 AI 服务，基于 Spring AI 实现 RAG 知识库问答，支持从 Word 文档中提取文字/表格/图片，回答问题时在对应位置展示文档原图。

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.2.5 + Spring AI 1.0.0-M6 |
| ORM | MyBatis-Plus 3.5.9 |
| 数据库 | OceanBase（MySQL 协议） |
| 向量库 | Redis Stack（RediSearch，docker 映射端口 **6380**） |
| LLM | 阿里云 MaaS 网关（OpenAI 兼容，chat=`qwen3.7-flash-2026-07-15`，embedding=`text-embedding-v4`，图片理解=`qwen3-vl:2b` 本地 Ollama） |
| 前端 | Vue 3 + Vite 5 + Ant Design Vue 4（Node ≥ 18，建议 20/22） |
| 文档解析 | Apache POI 5.2.3（段落/表格/内嵌图片） |

## 目录结构

```
dtbd-ai-service/
├── pom.xml                          # 后端 Maven 项目
├── src/main/java/.../ai/            # 后端源码
│   ├── AiApplication.java           # 入口
│   ├── config/                      # 配置类（含 ImageWebConfig 图片静态映射、GlobalExceptionHandler）
│   ├── controller/                  # SSE 聊天 + 文档管理 API
│   ├── service/                     # RagService / DocumentService / VisionService / SessionService
│   ├── model/                       # 实体
│   ├── mapper/                      # MyBatis Plus Mapper
│   └── dto/                         # 数据传输对象
├── src/main/resources/
│   ├── application.yml              # 配置
│   └── schema.sql                   # 建表脚本（启动自动执行，幂等可重复运行）
├── data/images/{docId}/             # 文档提取的图片（运行时生成，静态映射 /ai/images/**）
└── web/                             # 前端测试台（Node ≥ 18，见 .nvmrc）
    ├── package.json
    ├── vite.config.js               # /proxy → http://localhost:8090/ai
    └── src/views/Chat.vue           # AI 聊天页（含图片灯箱预览）
```

## 启动方式

### 1. 环境准备

**Redis Stack**（docker，端口映射 6380）：
```bash
docker run -d --name redis-stack -p 6380:6379 redis/redis-stack-server:latest
```
> ⚠️ Spring AI M6 的 RedisVectorStore 自动配置强制要求 **Jedis** 客户端，项目已引入 `redis.clients:jedis`，且 `spring.data.redis.client-type: jedis`。

**数据库**：使用现有 OceanBase 库 `dtbd_init`（库需预先存在）。表结构（`c_ai_document`/`c_ai_knowledge`/`c_ai_session`/`c_ai_message`）由应用启动时自动执行 `schema.sql` 创建（`spring.sql.init.mode=always`，全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全）；也可手动执行：
```bash
mysql -h172.168.10.65 -P2881 -uroot -p dtbd_init < src/main/resources/schema.sql
```

### 2. 配置环境变量

```bash
# ===== 必填（无默认值，缺失将启动失败 fail-fast）=====
export DB_PASSWORD=xxx                    # 数据库密码
export AI_TRUSTED_TOKEN=xxx               # 内部鉴权 token（与 dtbd-core 一致）
export AI_DEEPSEEK_KEY=sk-xxxx            # LLM/Embedding/视觉共用（MaaS 网关）

# ===== 可选 =====
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6380
export AI_VISION_MODEL=qwen3-vl:2b  # 图片描述模型（全模态，本地 Ollama）
export AI_VISION_BASE_URL=http://localhost:11434  # 视觉模型地址（不含 /v1，代码自动拼）
export AI_VISION_THINK=false       # 关闭 qwen3 思考模式（提速且输出稳定）
export AI_IMAGES_DIR=./data               # 图片存储目录
export AI_IMAGES_AUTH_ENABLED=false       # 图片访问鉴权（生产建议 true，HMAC 签名 URL）
export LOG_LEVEL_APP=info                 # 应用日志级别
```

**本地开发**：无需 export，把真实值直接写入 `src/main/resources/application-local.yml`（私有文件，已加入 .gitignore，不会提交），然后以 `local` profile 启动：
- IDEA：Run Configuration → Active profiles 填 `local`
- 命令行：`SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`

### 3. 启动后端

```bash
# 方式一：直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/dtbd-ai-service-1.0.0-SNAPSHOT.jar

# 方式三：Docker Compose（含 redis-stack）
docker compose up -d
```

后端启动后监听 `http://localhost:8090/ai`（context-path `/ai`），API 前缀 `/ai/api/ai/*`，
健康检查：`GET http://localhost:8090/ai/actuator/health`。

### 4. 启动前端测试台

```bash
cd web
nvm use            # 或使用 Node 20/22（.nvmrc 已固定 22）
npm install
npm run dev
```

前端启动后访问 `http://localhost:5800`。Vite 将 `/proxy/**` 代理到 `http://localhost:8090/ai`。
前端环境配置见 `web/.env.development`（开发）/ `web/.env.production`（生产，走平台网关路径）。

### 5. 使用流程

1. 打开前端 → **文档管理**，上传 `.docx` 操作手册（≤50MB）
2. 系统自动解析：段落文字、表格内容、内嵌图片（全部提取，压缩后并发调用视觉模型生成描述）
3. 切到 **智能问答** 提问，回答中会在对应位置插入文档截图，点击图片可全屏放大
4. 重新上传同名文件会自动替换旧版本（含向量与图片清理）

## 核心功能

- **RAG 问答**：手动检索 Top-K 知识块，构建上下文（含 `[图片N]` 位置标记），流式 SSE 输出
- **图片处理链路**：docx 提取图片（栅格类型过滤 + 文档内去重 + 最长边 768px 压缩）→ 存 `data/images/{docId}/` → 并发调用视觉模型转文字描述入分块 → 命中时 SSE 发 `image` 事件 → 前端渲染原图（点击灯箱放大）
- **SSE 事件**：`image`（图片 URL 数组，先发）→ `token*` → `done` / `error`

## 与报表平台集成

生产环境由 dtbd-core 的 `AiProxyController` 做 JWT 鉴权并透传请求（前端调 `/dtbd/api/ai/*`，见 `web/.env.production`）。
**注意**：
1. 平台网关需额外透传图片路径 `/ai/images/**`（生产开启图片鉴权时，图片 URL 带 HMAC 签名与过期时间，由本服务动态生成）
2. SSE 接口（`/chat`）网关需关闭响应缓冲，否则流式 token 无法实时到达
3. 内部 token `AI_TRUSTED_TOKEN` 由网关注入请求头，前端不携带共享密钥

## 测试与验证

```bash
# 后端单元测试（签名器/响应体契约等）
mvn test
# 注意：运行测试前需停止占用 target/ 的 IDE 服务实例

# 前端构建验证
cd web && npm run build
```

## 产品化特性

- **安全**：密钥零默认值（缺失 fail-fast）、token 恒定时间比较、图片访问 HMAC 签名 URL（`AI_IMAGES_AUTH_ENABLED=true`）、统一异常+参数校验（`@Valid`）、错误信息不泄露内部细节
- **可靠性**：上传失败自动补偿清理（删向量+MySQL+图片）、会话历史 Redis List+Lua 原子追加（带图片）、SSE 异步订阅支持停止生成
- **可观测性**：`/actuator/health` 健康检查、日志级别环境变量化、MyBatis 日志走 slf4j
- **前端体验**：会话历史恢复（含图片）、停止生成按钮、上传进度条、空态/描述输入/删除确认、Markdown 子集安全渲染（HTML 转义 + 图片属性转义）
- **部署**：multi-stage Dockerfile、docker-compose（含 redis-stack）、nginx 参考配置（`deploy/nginx.conf`）

## 配置说明

关键配置项（`application.yml`）：

```yaml
ai-app:
  chunk:
    max-size: 800          # 知识分块最大字符数
    overlap: 100
  retrieval:
    top-k: 5               # 检索返回片段数
    similarity-threshold: 0.5
  session:
    max-history: 10
    expire-minutes: 30
  images:
    dir: ./data            # 图片存储根目录（AI_IMAGES_DIR）
    max-width: 768         # 图片最长边像素，超过则等比压缩（0=不压缩；768 为速度/清晰度平衡点，视觉 token 约为 1280 的 1/3）
    quality: 0.8           # JPEG 压缩质量（带透明通道自动转 PNG）
    url-prefix: /ai/images # 图片 URL 前缀（含 context-path）
  vision:
    model: qwen3-vl:2b     # 图片描述模型（AI_VISION_MODEL，本地 Ollama）
    base-url: http://localhost:11434  # 不含 /v1，代码自动拼（AI_VISION_BASE_URL）
    api-key: ollama        # Ollama 不校验密钥，占位值；云端服务需真实 Key
    enabled: true
    timeout-millis: 60000  # 单张描述超时（本地 2B 模型实测约 29s）
    concurrency: 4         # 描述并发度（Ollama 需设 OLLAMA_NUM_PARALLEL 才真正并行）
    think: false           # 关闭 qwen3 思考模式，提速且输出稳定（AI_VISION_THINK）
  trusted-token: dtbd-ai-internal-token

spring:
  servlet:
    multipart:
      max-file-size: 50MB   # 上传限制（默认 1MB 会报 MaxUploadSizeExceededException）
      max-request-size: 100MB
  data:
    redis:
      client-type: jedis    # 必须 jedis（Spring AI M6 向量库自动配置要求）
      host: 127.0.0.1
      port: 6380            # docker 映射端口
  ai:
    openai:
      api-key: ${AI_DEEPSEEK_KEY}
      base-url: <MaaS 网关 /compatible-mode>   # 不含 /v1（Spring AI 自动补）
      chat: { options: { model: qwen3.7-flash-2026-07-15, temperature: 0.3 } }
      embedding:
        base-url: <MaaS 网关 /compatible-mode>
        options: { model: text-embedding-v4 }
    vectorstore:
      redis:
        initialize-schema: true
        index: dtbd-ai-index        # 注意配置项是 index，不是 index-name
        prefix: "ai:chunk:"
```

## 已知注意事项

- **聊天模型**：当前使用 `qwen3.7-flash-2026-07-15`（与 application.yml 一致）。注意：该 MaaS 网关对部分模型（如 `qwen-max`）返回 DashScope 原生格式（`{"text":...}`），Spring AI 无法解析（表现为 0 token 无回答）；需使用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 已实测兼容）。
- **base-url 不含 `/v1`**：Spring AI 会自动拼 `/v1/chat/completions`、`/v1/embeddings`；手写 HTTP 调用（VisionService）也会自动补 `/v1`（base-url 已以 `/v1` 结尾时不重复拼接）。
- **图片访问路径**：后端返回 `/ai/images/...`（含 context-path），前端经 `/proxy` 代理时需去掉 `/ai` 前缀（vite 代理 target 已含 context-path），否则双重 `/ai` 404。
- **M6 向量库 metadata 限制**：RedisVectorStore 检索返回的 Document 仅含相似度分数，自定义 metadata（如 images）需用 `doc.getId()`（=knowledgeId）查 MySQL 兜底。
- **图片描述失败降级**：视觉模型调用失败时图片仍会提取保存，描述降级为 `[图片]` 占位，不影响上传与问答。

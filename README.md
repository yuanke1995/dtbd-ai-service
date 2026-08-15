# DTBD AI Service

报表平台独立 AI 服务，基于 Spring AI 实现 RAG 知识库问答。支持 Word/PDF/Excel 文档解析（含扫描 PDF OCR）、混合检索 + 查询改写、价值驱动上下文控制、回答中位置级展示文档原图、引用溯源、会话管理（搜索/置顶/收藏）、文档版本管理、数据看板与知识缺口闭环，是面向"操作手册问答"场景的完整智能助手。

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.5.15 + Spring AI 1.1.8 |
| ORM | MyBatis-Plus 3.5.12 |
| 数据库 | OceanBase（MySQL 协议，库 `dtbd_init`） |
| 向量库 | Redis Stack（RediSearch，docker 映射端口 **6380**，Jedis 客户端） |
| LLM | 阿里云 MaaS 网关（OpenAI 兼容：chat=`qwen3.7-flash-2026-07-15`，embedding=`qwen3.7-text-embedding`，base-url 不含 `/v1`）；图片理解/OCR 走本地 Ollama `qwen3-vl:2b` |
| 文档解析 | Apache POI 5.2.3（docx/xlsx）+ PDFBox 3.0.2（含扫描件 OCR 降级） |
| 前端 | Vue 3 + Vite 5 + Ant Design Vue 4 + markdown-it/DOMPurify/highlight.js（Node ≥ 18，建议 20/22） |

## 目录结构

```
dtbd-ai-service/
├── pom.xml                          # 后端 Maven 项目（spring-ai-bom 统一管理版本）
├── src/main/java/.../ai/
│   ├── AiApplication.java           # 入口
│   ├── config/                      # AiAppProperties / SecurityConfig / ImageWebConfig / GlobalExceptionHandler / ImageAuthInterceptor
│   ├── controller/                  # Chat(SSE) / Document / Qa(反馈+看板) / Config(模型配置) / AiKnowledge(缺口回流+编辑) / RetrievalDebug(检索调试)
│   ├── service/                     # RagService / HybridRetrievalService / RerankService / KeywordExtractor / ImageFilterService
│   │                                # DocumentService / VisionService / UserImageService / SessionService / QaLogService / ConfigService / DocumentMetaCache ...
│   ├── parser/                      # DocumentParser 接口 + DocxParser / PdfParser(OCR) / ExcelParser
│   ├── util/                        # TokenCounter（分语言 token 估算，上下文预算用）
│   ├── model/ + mapper/ + dto/      # 实体（含 AiDocumentVersion）/ MyBatis-Plus Mapper / 传输对象
├── src/main/resources/
│   ├── application.yml              # 配置（密钥零默认值，缺失 fail-fast）
│   ├── application-local.yml        # 本地开发私有配置（含数据目录，.gitignore 忽略）
│   └── schema.sql                   # 建表脚本（启动自动执行，幂等可重复运行）
├── data/                            # 运行时生成：files/{docId}/ 源文件 + images/{docId}/ 提取图 + images/chat/ 用户图
├── deploy/nginx.conf                # 生产 nginx 参考配置
└── web/                             # 前端单页应用（Vite，.nvmrc 固定 Node 22）
    ├── vite.config.js               # /proxy → http://localhost:8090/ai（端口固定 5800，strictPort）
    └── src/views/                   # Chat(智能问答) / Documents(文档管理) / Dashboard(数据看板) / Settings(系统设置)
```

## 启动方式

### 1. 环境准备

**Redis Stack**（docker，端口映射 6380）：
```bash
docker run -d --name redis-stack -p 6380:6379 redis/redis-stack-server:latest
```
> RedisVectorStore 自动配置使用 **Jedis** 客户端，项目已引入 `redis.clients:jedis` 且 `spring.data.redis.client-type: jedis`。

**本地 Ollama**（图片描述 / 扫描 PDF OCR）：安装 Ollama 并拉取视觉模型：
```bash
ollama pull qwen3-vl:2b
```
> 建议设置环境变量 `OLLAMA_NUM_PARALLEL=4`（否则多图描述串行排队）；4GB 显存机器并发度建议 `vision.concurrency=2`。

**数据库**：现有 OceanBase 库 `dtbd_init`（库需预先存在）。表结构（`c_ai_document`/`c_ai_knowledge`/`c_ai_session`/`c_ai_message`/`c_ai_qa_log`/`c_ai_qa_feedback`/`c_ai_config`/`c_ai_document_version`）由应用启动自动执行 `schema.sql` 创建（全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全）；也可手动执行：
```bash
mysql -h172.168.10.65 -P2881 -uroot -p dtbd_init < src/main/resources/schema.sql
```

### 2. 配置环境变量

```bash
# ===== 必填（无默认值，缺失将启动失败 fail-fast）=====
export DB_PASSWORD=xxx                    # 数据库密码
export AI_TRUSTED_TOKEN=xxx               # 内部鉴权 token（与 dtbd-core 一致）
export AI_DEEPSEEK_KEY=sk-xxxx            # chat 模型密钥（MaaS 网关）

# ===== 可选 =====
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export AI_VISION_MODEL=qwen3-vl:2b        # 图片描述/OCR 模型（本地 Ollama）
export AI_VISION_BASE_URL=http://localhost:11434  # 视觉地址（不含 /v1，代码自动拼）
export AI_VISION_THINK=false              # 关闭 qwen3 思考模式（提速且输出稳定）
export AI_IMAGES_DIR=./data               # 数据落盘目录（跨平台兜底；生产容器内为 /app/data）
export AI_IMAGES_AUTH_ENABLED=false       # 图片访问鉴权（生产建议 true，HMAC 签名 URL）
export AI_QUERY_REWRITE_ENABLED=true      # 查询改写开关（默认开启）
export AI_IMAGE_FILTER_ENABLED=true       # 回答图片相关性校验开关（默认开启）
export LOG_LEVEL_APP=info                 # 应用日志级别
```

**本地开发**：无需 export，把真实值直接写入 `src/main/resources/application-local.yml`（私有文件，已加入 .gitignore；**数据目录 `ai-app.images.dir` 也在此配置**，Windows 机器改成 `D:/workspace/dtbd-ai-service/data` 即可，天然区分平台），然后以 `local` profile 启动（application.yml 已默认激活 local）：
- IDEA：Run Configuration → Active profiles 填 `local`
- 命令行：`SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`

### 3. 启动后端

```bash
# 方式一：直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/dtbd-ai-service-1.0.1-SNAPSHOT.jar

# 方式三：Docker Compose（含 redis-stack）
docker compose up -d
```

后端监听 `http://localhost:8090/ai`（context-path `/ai`），API 前缀 `/api/ai/*`，
健康检查：`GET http://localhost:8090/ai/actuator/health`。

### 4. 启动前端

```bash
cd web
nvm use            # 或使用 Node 20/22（.nvmrc 已固定 22）
npm install
npm run dev        # 访问 http://localhost:5800（端口被占直接报错，不会跳号）
```

Vite 将 `/proxy/**` 代理到 `http://localhost:8090/ai`。环境配置见 `web/.env.development`（开发）/ `web/.env.production`（生产，走平台网关路径）。

### 5. 使用流程

1. **文档管理**：上传 `.docx` / `.pdf` / `.xlsx`（≤50MB，可多文件，可指定分类）→ 异步解析（解析中状态轮询）→ 生效/弃用/重解析/批量操作/命中次数统计；点击"知识块"可预览该文档的分块与图片（支持**编辑/删除单个知识块**，编辑后自动重新向量化）；支持**分类筛选**；**版本管理**（每次解析/重解析自动存快照，可查看历史版本并一键回滚）
2. **智能问答**：提问（支持上传图片+问题）→ 混合检索 + 查询改写 → 流式回答，位置级插入文档原图、[N] 引用角标（点击弹窗看来源全文与图片）、末尾相关追问；回答下方操作区（检索调试/重新生成/复制 Markdown/导出 .md/👍👎 反馈，hover 显示）；断连自动重试（内联提示）
3. **会话管理**：侧边栏搜索（标题/内容模糊匹配）、**置顶/收藏**（置顶排最前，收藏 tab 筛选）、拖拽伸缩、删除/清空
4. **数据看板**：统计卡片（问答量/满意率/引用率/无命中率）+ 热门问题/无命中 TOP10 + **知识库缺口管理**（无命中问题一键入库，含向量召回）
5. **系统设置**：模型名/温度/System Prompt（角色段）/视觉模型/检索权重/上下文参数，**保存即生效**（DB 存储，分栏折叠展示，参数带 `?` 说明）

## 核心功能

- **混合检索**：Redis 向量 Top-K（阈值 0.5）+ MySQL 关键词 LIKE（800ms 超时）并行召回；**向量分归一化 + 双命中叠加**（语义+关键词命中 = 向量分+关键词分+标题奖励）；关键词按 **tf×idf 词频加权**（标题词频×2）；**中文分词增强**（纯中文 2-gram / 中英混合 4-gram 子词元）；**分块位置惩罚**（文档首块加权）；Ollama 支持 rerank 时自动启用，否则回退规则排序
- **查询改写**：LLM 将用户问题改写为检索关键词（默认开启）；支持多轮对话上下文改写（追问"那删除呢？"自动补全），改写结果入库可评估
- **上下文与长度控制**：`预算 = min(模型窗口×安全系数 − 输出限制, 成本软上限)`，窗口按当前模型动态匹配；**价值驱动填充**（知识块按相关度分数累积填充，替代固定 8 块）；**块内命中片段截取**（±150 字窗口，边界对齐行/图片标记，长命令不被切断）；**历史裁剪**（单条 200 字 + 总量上限 + 剥离 `[图片N]`）；输出 maxTokens 限制；token 按中英文分语言估算（TokenCounter）
- **图片链路**：docx 提取图片（去重 + 768px 压缩）→ 视觉模型生成描述并随分块落库 → 检索命中后全局编号 `[图片N：描述]` 供 LLM 选图 → **相关性校验兜底**（`[图片N]` 与描述/上下文不匹配自动剔除并重建编号，防错配）→ SSE `image` 事件 → 前端按标记渲染原图（灯箱：缩放/拖动/多图切换/ESC）
- **引用溯源**：回答句末 `[N]` 角标 → 弹窗展示来源知识块全文（图文交错，还原原文结构）+ 关联截图；`done` 事件携带 sources/related/messageId
- **文档解析**：docx（段落/标题大纲级别/**表格→Markdown 表格、单列表格→代码块**/内嵌图/单元格换行保留）/ xlsx（sheet 转文本）/ pdf（PDFBox 文本抽取）；**扫描件自动 OCR**（文本 <20 字符判定，逐页渲染 150DPI → 本地视觉模型识别，OCR 专用提示词）；分块 800 字 + 100 字重叠（重叠已生效）
- **数据闭环**：问答日志（含改写后问题/命中文档/耗时）+ 回答 👍👎 反馈 + 看板聚合；**无命中问题汇总 → 一键创建知识块（自动生成向量）**，形成"发现缺口→补充→验证"闭环
- **检索调试**：`POST /api/ai/debug/retrieval` 分步展示检索词元（分词结果）/关键词/向量/合并/重排/最终结果与命中率，前端问答页"检索调试"按钮可视化排查召回问题
- **会话**：MySQL + Redis 双层存储，历史恢复（含图片/引用来源/messageId）、删除/清空、**搜索/置顶/收藏**、侧边栏拖拽伸缩（宽度记忆）
- **前端体验**：markdown-it + DOMPurify + highlight.js 安全渲染（代码块复制按钮、**长行自动折行 + 限高滚动**）、重新生成/编辑重问（编辑图标悬浮气泡下方）、图片灯箱、问答 👍👎 反馈、断连自动重试内联提示、上传进度条

## API 一览

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（token/image/done/error 事件） |
| `GET /api/ai/sessions?keyword=`、`POST /api/ai/session/new`、`GET /api/ai/session/{id}` | 会话列表（支持关键词搜索）/ 新建 / 历史恢复 |
| `PUT /api/ai/session/{id}/pin`、`PUT /api/ai/session/{id}/favorite` | 置顶 / 收藏（`{pinned:true}` 或 `{favorite:true}`） |
| `DELETE /api/ai/session/{id}`、`DELETE /api/ai/sessions` | 删除单会话（软删除会话+消息）/ 清空全部会话 |
| `POST /api/ai/document/upload`、`/upload/batch` | 上传文档（docx/pdf/xlsx，异步解析，支持 category 分类） |
| `GET /api/ai/document/list`、`DELETE /{id}`、`PUT /{id}/status`、`POST /{id}/reparse` | 文档列表 / 删除 / 启停用 / 重解析 |
| `GET /api/ai/document/categories`、`PUT /{id}/category` | 分类列表 / 修改文档分类 |
| `GET /api/ai/document/{id}/versions`、`POST /{id}/rollback` | 版本历史 / 回滚到指定版本（按原 ID 重建知识块+向量） |
| `POST /api/ai/document/batch/delete`、`/batch/status`、`GET /document/stats` | 批量操作 + 命中次数统计 |
| `GET /api/ai/knowledge/{id}`、`GET /api/ai/knowledge/list?docId=` | 知识块详情（引用溯源） / 按文档预览 |
| `PUT /api/ai/knowledge/{id}`、`DELETE /api/ai/knowledge/{id}` | 编辑知识块（重新向量化：删旧向量+插新）/ 删除知识块 |
| `GET /api/ai/knowledge/unmatched`、`POST /api/ai/knowledge` | 无命中问题列表 / 手动创建知识块（自动生成向量） |
| `POST /api/ai/feedback`、`GET /api/ai/analytics/summary` | 回答反馈 / 看板聚合 |
| `GET/PUT /api/ai/config` | 模型配置读取（apiKey 脱敏）/ 保存即生效（含检索权重、上下文参数） |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试（含检索词元） |

## 与报表平台集成

生产环境由 dtbd-core 的 `AiProxyController` 做 JWT 鉴权并透传请求（前端调 `/dtbd/api/ai/*`，见 `web/.env.production`）。
**注意**：
1. 平台网关需额外透传图片路径 `/ai/images/**`（生产开启图片鉴权时，图片 URL 带 HMAC 签名与过期时间，由本服务动态生成）
2. SSE 接口（`/chat`）网关需关闭响应缓冲，否则流式 token 无法实时到达
3. 内部 token `AI_TRUSTED_TOKEN` 由网关注入请求头，前端不携带共享密钥

## 测试与验证

```bash
# 后端单元测试（ImageUrlSigner / ResultJson / ImageFilterService 图片校验 等）
mvn test
# 注意：运行测试前需停止占用 target/ 的 IDE 服务实例

# 检索评估集（需真实 Redis/DB，默认 @Disabled，按需开启）
# 填充 src/test/resources/retrieval-eval.json 的知识块 ID 后执行 RetrievalEvaluationTest
# 输出 recall@5 / MRR 基线，防止检索改动回归

# 前端构建验证
cd web && npm run build
```

## 产品化特性

- **安全**：密钥零默认值（缺失 fail-fast）、token 恒定时间比较、图片访问 HMAC 签名 URL（`AI_IMAGES_AUTH_ENABLED=true`）、统一异常+参数校验（`@Valid`）、错误信息不泄露内部细节
- **可靠性**：上传失败自动补偿清理（删向量+MySQL+图片）、脏解析记录清理、解析异步化（不阻塞上传）、SSE 异步订阅支持停止生成、查询改写专用线程池（超时隔离 + daemon + PreDestroy 回收）
- **可配置**：模型名/温度/System Prompt 角色段/视觉提示词/检索权重/**上下文参数** **数据库存储、保存即生效**（`c_ai_config`，存量升级自动补默认项）；prompt 调整无需重启
- **可观测性**：`/actuator/health` 健康检查、日志级别环境变量化、MyBatis 日志走 slf4j、检索调试 API
- **部署**：multi-stage Dockerfile、docker-compose（含 redis-stack）、nginx 参考配置（`deploy/nginx.conf`，SPA fallback + SSE 关缓冲 + 图片缓存）

## 配置说明

关键配置项（`application.yml`，完整默认值见 [AiAppProperties.java](src/main/java/com/wisesoft/ai/config/AiAppProperties.java)）：

```yaml
ai-app:
  chunk: { max-size: 800, overlap: 100 }   # 分块大小/重叠（已生效）
  retrieval:
    top-k: 5                               # 上下文用命中块数（重排候选另算）
    similarity-threshold: 0.5
    vector-weight: 0.6                     # 混合检索：向量权重（DB c_ai_config 可覆盖，设置页保存即生效）
    keyword-weight: 0.4                    # 混合检索：关键词权重
    title-bonus: 0.1                       # 混合检索：标题命中奖励
  context:                                 # 上下文与长度控制（设置页可调，保存即生效）
    model-windows: "qwen-plus=131072,qwen3=131072,deepseek=65536,..."  # 模型窗口映射（按 chat.model 子串匹配）
    default-window-tokens: 32768
    safety-factor: 0.7                     # 窗口安全系数（预算 = 窗口×系数 − 输出）
    cost-cap-tokens: 8000                  # 成本软上限（0=不限制）
    max-output-tokens: 2000                # 输出限制
    history-max-tokens: 1200               # 历史注入上限
    history-per-msg-chars: 200             # 单条历史截断
    snippet-window-chars: 150              # 知识块命中片段窗口（0=整块）
    max-context-hits: 8                    # 知识块填充上限
  session: { max-history: 10, expire-minutes: 30 }
  images:
    dir: ${AI_IMAGES_DIR:./data}           # 数据根目录（开发在 application-local.yml 配绝对路径；生产 /app/data）
    max-width: 768                         # 图片最长边（视觉 token 约为 1280 的 1/3）
    quality: 0.8
    url-prefix: /ai/images
    auth-enabled: ${AI_IMAGES_AUTH_ENABLED:false}
    auth-expire-seconds: 3600
    image-filter:                          # 回答 [图片N] 相关性校验（防 LLM 错配）
      enabled: ${AI_IMAGE_FILTER_ENABLED:true}
      min-hits: 1                          # 描述 2 字窗口命中数阈值（宁漏检勿误杀）
      pre-context-chars: 100               # 取标记前文最大字符数
  vision:
    model: ${AI_VISION_MODEL:qwen3-vl:2b}  # 本地 Ollama（不含 /v1，代码自动拼）
    base-url: ${AI_VISION_BASE_URL:http://localhost:11434}
    api-key: ollama                        # Ollama 不校验密钥，占位值
    enabled: true
    timeout-millis: 180000                 # 单张描述超时（图多排队+推理）
    concurrency: 2                         # 并发度（4GB 显存建议 2；Ollama 需 OLLAMA_NUM_PARALLEL 配合）
    retry-count: 1                         # 单图失败重试
    keep-alive-minutes: 30                 # 模型常驻内存（云端服务需设 0）
    think: ${AI_VISION_THINK:false}        # 关闭 qwen3 思考（实测 max_tokens 会导致空输出，勿加）
  query-rewrite:                           # 查询改写（默认开启）
    enabled: ${AI_QUERY_REWRITE_ENABLED:true}
    timeout-millis: 5000
    history-rounds: 2                      # 多轮改写参与轮数
    prompt / prompt-multi-turn             # 改写 prompt（多轮含 %s 历史占位）
  system-prompt: "你是\"小报\"..."          # 回答角色段默认值（DB c_ai_config 可覆盖，保存即生效）
  trusted-token: ${AI_TRUSTED_TOKEN}

spring:
  servlet.multipart: { max-file-size: 50MB, max-request-size: 100MB }
  data.redis: { client-type: jedis, host: ${REDIS_HOST:127.0.0.1}, port: ${REDIS_PORT:6379} }
  ai.openai:
    api-key: ${AI_DEEPSEEK_KEY}
    base-url: <MaaS 网关 /compatible-mode> # 不含 /v1（Spring AI 自动补）
    chat: { options: { model: qwen3.7-flash-2026-07-15, temperature: 0.3 } }
    embedding: { base-url: ... , options: { model: qwen3.7-text-embedding } }
  ai.vectorstore.redis: { initialize-schema: true, index-name: dtbd-ai-index, prefix: "ai:chunk:" }  # Spring AI 1.1 起属性为 index-name
```

> **System Prompt 外置边界**：仅"角色与回答风格"段可编辑（设置页）；引用 `[N]` / 图片 `[图片N]` / 追问 `<related>` 规则与后端解析器强耦合，保留代码固定，避免改坏导致解析失效。

## 已知注意事项

- **聊天模型**：当前使用 `qwen3.7-flash-2026-07-15`。该 MaaS 网关对部分模型（如 `qwen-max`）返回 DashScope 原生格式（`{"text":...}`），Spring AI 无法解析（表现为 0 token 无回答）；需使用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 已实测兼容）
- **base-url 不含 `/v1`**：Spring AI 与 VisionService 都会自动补 `/v1`；视觉 base-url 以 `/v1` 结尾时也不会重复拼接
- **Spring AI 版本**：1.1.8 起 starter 更名（`spring-ai-starter-model-openai` / `spring-ai-starter-vector-store-redis`），由 `spring-ai-bom` 统一管理；RedisVectorStore 配置属性 `index` → `index-name`，`initialize-schema` 默认 false 需显式开启。**升级 1.1 后旧向量数据建议重新上传/重解析文档**（序列化结构可能变化）
- **图片访问路径**：后端返回 `/ai/images/...`（含 context-path），前端经 `/proxy` 代理时需去掉 `/ai` 前缀（vite 代理 target 已含 context-path），否则双重 `/ai` 404
- **图片描述失败降级**：视觉模型调用失败时图片仍会提取保存，描述降级为 `[图片]` 占位，不影响上传与问答；此时回答图片相关性校验对无描述图自动放行
- **存量库升级**：`c_ai_config.config_value` 需为 TEXT（容纳长 prompt）；新增列（`c_ai_session.is_pinned/is_favorite`、`c_ai_document.category/version`）与新表（`c_ai_document_version`）——新表启动自动建，**存量表加列需手动执行 ALTER**（见 schema.sql 注释）
- **视觉模型思考模式**：qwen3 系列 `max_tokens` 限制会导致内容为空（思考耗尽 token），VisionService 不发送 max_tokens、改用 `think: false`
- **多图描述性能**：图片描述耗时与并发强相关，Ollama 需设 `OLLAMA_NUM_PARALLEL` 才能真正并行；文档重传会重新生成全部图片描述（77 图约 5-10 分钟）

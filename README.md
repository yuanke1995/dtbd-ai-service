# AI 文档助手

报表平台独立 AI 服务，基于 Spring AI 实现 RAG 知识库问答。支持 Word/PDF/Excel 文档解析（含扫描 PDF OCR）、混合检索 + 查询改写、价值驱动上下文控制、回答中位置级展示文档原图（识别用压缩图、展示用原图的双图策略）、解析进度实时展示（含图片识别逐张进度）、引用溯源、会话管理（搜索/置顶/收藏）、文档版本管理、数据看板与知识缺口闭环，是面向"操作手册问答"场景的完整智能助手。

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.5.15 + Spring AI 1.1.8 |
| ORM | MyBatis-Plus 3.5.12 |
| 数据库 | OceanBase（MySQL 协议，库 `ai_doc_assistant`，可按环境调整） |
| 向量库 | Redis Stack（RediSearch，docker 映射端口 **6380**，Jedis 客户端） |
| LLM | 阿里云 MaaS 网关（OpenAI 兼容：chat=`qwen3.7-flash-2026-07-15`，embedding=`qwen3.7-text-embedding`，base-url 不含 `/v1`）；图片理解/OCR 走本地 Ollama `qwen3-vl:2b` |
| 文档解析 | Apache POI 5.2.3（docx/xlsx）+ PDFBox 3.0.2（含扫描件 OCR 降级）+ jieba-analysis 1.0.2（中文分词） |
| 前端 | Vue 3 + Vite 5 + Ant Design Vue 4 + markdown-it/DOMPurify/highlight.js（Node ≥ 18，建议 20/22） |

## 目录结构

```
ai-doc-assistant/
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
│   ├── application.yml              # 配置（关键密钥无默认值：DB_PASSWORD/AI_TRUSTED_TOKEN 缺失 fail-fast）
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

**数据库**：现有 OceanBase 库 `ai_doc_assistant`（库需预先存在，库名按实际环境配置）。表结构（`c_ai_document`/`c_ai_knowledge`/`c_ai_session`/`c_ai_message`/`c_ai_qa_log`/`c_ai_qa_feedback`/`c_ai_config`/`c_ai_document_version`）由应用启动自动执行 `schema.sql` 创建（全部 `CREATE TABLE IF NOT EXISTS`，重复启动安全）；也可手动执行：
```bash
mysql -h172.168.10.65 -P2881 -uroot -p ai_doc_assistant < src/main/resources/schema.sql
```

### 2. 配置环境变量

```bash
# ===== 必填（无默认值，缺失将启动失败 fail-fast）=====
export DB_PASSWORD=xxx                    # 数据库密码
export AI_TRUSTED_TOKEN=xxx               # 内部鉴权 token（与平台网关一致，SecurityConfig 校验）

# ===== 可选 =====
export AI_DEEPSEEK_KEY=sk-xxxx            # chat 模型密钥（MaaS 网关；有默认空值，缺失不启动失败，但聊天不可用）
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379               # Redis 端口（本机环境覆盖为 6380：docker 映射）
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
java -jar target/ai-doc-assistant.jar

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

1. **文档管理**：上传 `.docx` / `.pdf` / `.xlsx`（默认 ≤200MB，可多文件；上限在设置页调整，物理上限 1GB）→ 异步解析（解析中状态轮询，进度条实时展示：图片识别阶段逐张显示"识别图片 k/total"）→ 生效/弃用/重解析/批量操作/命中次数统计；**解析中删除文档立即中断**（线程中断 + 产物自动清理，无孤儿数据）；点击"知识块"可预览该文档的分块与图片（支持**编辑/删除单个知识块**，编辑后自动重新向量化）；**版本管理**（每次解析/重解析自动存快照，可查看历史版本并一键回滚）；**结构感知切分**（docx 默认开启：按标题层级开新块、段落边界断块、表格独立成块；**正文只存净内容**，章节路径 `【上下文】章节 > 小节` 独立存储，向量化与检索回答时再拼入上下文，需重解析生效）
2. **智能问答**：提问（支持上传图片+问题）→ 混合检索 + 查询改写 → 流式回答，位置级插入文档原图、[N] 引用角标（点击弹窗看来源全文与图片）、末尾相关追问；回答下方操作区（检索调试/重新生成/复制 Markdown/导出 .md/👍👎 反馈，hover 显示）；断连自动重试（内联提示）
3. **会话管理**：侧边栏搜索（标题/内容模糊匹配）、**置顶/收藏**（置顶排最前，收藏 tab 筛选）、拖拽伸缩、删除/清空
4. **数据看板**：统计卡片（问答量/满意率/引用率/无命中率）+ 热门问题/无命中 TOP10 + **知识库缺口管理**（无命中问题一键入库，含向量召回）
5. **系统设置**：模型名/温度/System Prompt（角色段）/视觉模型/检索权重与行为参数/重排区间/解析并发与结构切分/上下文参数，**保存即生效**（DB 存储，折叠分组展示，顶部**锚点导航**快速定位 + 滚动高亮，参数带 `?` 说明）；问答输入框左侧深度思考开关（图标按钮，localStorage 记忆）
6. **检索评估**（顶栏入口）：从历史问答引用回放生成评估集（问题→期望知识块）→ 批量参数组对比（recall@k/MRR/命中率 + 弃用文档召回断言）→ 逐问题命中明细；参数覆盖不污染当前配置，用于量化验证调参/切分/分词改动是好是坏

## 核心功能

- **混合检索**：Redis 向量 Top-K + 关键词召回并行（**超时/阈值/召回数/位置奖励等行为参数设置页可调**，保存即生效）；**向量分归一化 + 双命中叠加**（语义+关键词命中 = 向量分+关键词分+标题奖励）；**关键词引擎可切换**（默认 `mysql` LIKE 零依赖；切到 `meilisearch` 用中文分词 + 相关度打分，服务不可用/超时自动降级回 MySQL，首次切换需 `POST /api/ai/search-index/reindex` 全量重建，写索引随解析/编辑/删除增量同步）；**jieba 中文分词**（搜索模式细粒度词元 + 长词 2-gram/4-gram 子词元补充召回宽度，启动预热词典）；**分块位置奖励**（文档首块加权）；Ollama 支持 rerank 时自动启用（候选数在可配置区间内触发），否则回退规则排序
- **查询改写**：LLM 将用户问题改写为检索关键词（默认开启）；支持多轮对话上下文改写（追问"那删除呢？"自动补全），改写结果入库可评估
- **上下文与长度控制**：`预算 = min(模型窗口×安全系数 − 输出限制, 成本软上限)`，窗口按当前模型动态匹配；**价值驱动填充**（知识块按相关度分数累积填充，替代固定 8 块）；**块内命中片段截取**（±150 字窗口，边界对齐行/图片标记，长命令不被切断）；**历史裁剪**（单条 200 字 + 总量上限 + 剥离 `[图片N]`）；输出 maxTokens 限制；token 按中英文分语言估算（TokenCounter）
- **图片链路**：docx 提取图片（去重 + **双图策略**：识别用压缩图 1280px 进视觉模型，**展示用原图**落盘）→ 视觉模型生成描述并随分块落库（Ollama `num_ctx=16384` 防 1280px 视觉 token 截断）→ 检索命中后全局编号 `[图片N：描述]` 供 LLM 选图 → **相关性校验兜底**（`[图片N]` 与描述/上下文不匹配自动剔除并重建编号，防错配）→ SSE `image` 事件 → 前端按标记渲染原图（灯箱：缩放/拖动/多图切换/ESC）；图片描述完成逐张上报进度（10→30 区间"识别图片 k/total"）
- **引用溯源**：回答句末 `[N]` 角标 → 弹窗展示来源知识块全文（图文交错，还原原文结构）+ 关联截图；`done` 事件携带 sources/related/messageId
- **文档解析**：docx（段落/标题大纲级别/**表格→Markdown 表格、单列表格→代码块**/内嵌图/单元格换行保留）/ xlsx（sheet 转文本）/ pdf（PDFBox 文本抽取）；**扫描件自动 OCR**（文本 <20 字符判定，逐页渲染 200DPI → 本地视觉模型识别，OCR 专用提示词）；**结构感知切分**（docx：标题层级开新块 + 章节路径独立存储、向量化/检索时拼入 `【上下文】章节 > 小节`、达到边界阈值在段落交界断块、表格独立成块；`chunk.structural` 可关，需重解析生效）；**分块重叠只进向量化文本**（不入库、不进指纹，邻块变动不连锁重嵌）；**解析删除感知**（内存删除标志 + 线程中断，删除立即停止并清理本次产物）
- **数据闭环**：问答日志（含改写后问题/命中文档/耗时）+ 回答 👍👎 反馈 + 看板聚合；**无命中问题汇总 → 一键创建知识块（自动生成向量）**，形成"发现缺口→补充→验证"闭环
- **检索调试**：`POST /api/ai/debug/retrieval` 分步展示检索词元（分词结果）/关键词/向量/合并/重排/最终结果与命中率，前端问答页"检索调试"按钮可视化排查召回问题
- **检索评估**：`POST /api/ai/eval/generate` 从历史问答引用（`c_ai_message.sources`）回放生成评估集（问题→期望知识块，失效期望块自动剔除并计数）→ `POST /api/ai/eval/run` 批量参数组对比 **recall@k / MRR / 命中率** + 弃用文档召回断言；参数覆盖走线程局部 override，**不写 DB 不污染生产配置**（multi 模式为确定性拆分近似，衡量多路合并机制而非 LLM 深度思考质量）
- **会话**：MySQL + Redis 双层存储，历史恢复（含图片/引用来源/messageId）、删除/清空、**搜索/置顶/收藏**、侧边栏拖拽伸缩（宽度记忆）
- **前端体验**：markdown-it + DOMPurify + highlight.js 安全渲染（代码块复制按钮、**长行自动折行 + 限高滚动**）、重新生成/编辑重问（编辑图标悬浮气泡下方）、图片灯箱、问答 👍👎 反馈、断连自动重试内联提示、上传进度条

## API 一览

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（token/image/done/error 事件） |
| `GET /api/ai/sessions?keyword=`、`POST /api/ai/session/new`、`GET /api/ai/session/{id}` | 会话列表（支持关键词搜索）/ 新建 / 历史恢复 |
| `PUT /api/ai/session/{id}/pin`、`PUT /api/ai/session/{id}/favorite` | 置顶 / 收藏（`{pinned:true}` 或 `{favorite:true}`） |
| `DELETE /api/ai/session/{id}`、`DELETE /api/ai/sessions` | 删除单会话（软删除会话+消息）/ 清空全部会话 |
| `POST /api/ai/document/upload`、`/upload/batch` | 上传文档（docx/pdf/xlsx，异步解析；category 参数保留兼容，前端已不再传） |
| `GET /api/ai/document/list`、`DELETE /{id}`、`PUT /{id}/status`、`POST /{id}/reparse` | 文档列表 / 删除 / 启停用 / 重解析 |
| `GET /api/ai/document/categories`、`PUT /{id}/category` | 分类列表 / 修改文档分类（接口保留，前端分类 UI 已移除） |
| `GET /api/ai/document/{id}/versions`、`POST /{id}/rollback` | 版本历史 / 回滚到指定版本（按原 ID 重建知识块+向量） |
| `POST /api/ai/document/batch/delete`、`/batch/status`、`GET /document/stats` | 批量操作 + 命中次数统计 |
| `GET /api/ai/knowledge/{id}`、`GET /api/ai/knowledge/list?docId=` | 知识块详情（引用溯源） / 按文档预览 |
| `PUT /api/ai/knowledge/{id}`、`DELETE /api/ai/knowledge/{id}` | 编辑知识块（重新向量化：删旧向量+插新）/ 删除知识块 |
| `GET /api/ai/knowledge/unmatched`、`POST /api/ai/knowledge` | 无命中问题列表 / 手动创建知识块（自动生成向量） |
| `POST /api/ai/feedback`、`GET /api/ai/analytics/summary` | 回答反馈 / 看板聚合 |
| `GET/PUT /api/ai/config`、`GET /api/ai/config/keyword/check` | 模型配置读取（apiKey 脱敏）/ 保存即生效（含检索权重、上下文参数）/ 探测 Meilisearch 可用性 |
| `GET /api/ai/search-index/stats`、`POST /api/ai/search-index/reindex`、`DELETE /api/ai/search-index` | 关键词索引运维：状态统计（indexedCount vs mysqlCount 对比漂移）/ 全量重建（后台执行）/ 清空 |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试（含检索词元） |
| `POST /api/ai/eval/generate`、`GET /api/ai/eval/set`、`POST /api/ai/eval/run` | 检索量化评估：从历史问答引用回放生成评估集 / 读取评估集 / 批量参数组对比（recall@k/MRR/命中率 + 弃用文档召回断言） |

## 多副本部署要求

支持多实例水平扩展，需满足以下约束（均已代码化治理）：

1. **数据目录必须共享**：`AI_IMAGES_DIR` 指向所有实例都能访问的同一存储（源文件/提取图/评估集都在此）。docker-compose 用命名卷 `app-data:/app/data` 仅**同主机**多副本共享；跨主机（集群）需挂 NFS/对象存储等共享卷，否则副本 A 上传的文档在副本 B 无法重解析、图片 URL 在 B 侧 404
2. **静态配置一致**：各副本的 yml/环境变量（数据库、Redis、`AI_TRUSTED_TOKEN`、模型密钥等）必须一致；动态配置（`c_ai_config`）无需手工同步——任意实例保存后经 **Redis pub/sub**（channel `ai:config:changed`）广播，其他实例立即重载缓存；订阅断线期间的变更由 **5 分钟兜底轮询**补齐
3. **并发防护**：重解析用 **DB 状态机 CAS**（`SET status=2 WHERE status≠2`，原子）——两实例同时重解析同一文档只有一个成功，另一个返回"正在解析中"；解析队列有界（50）+ 图片描述线程池有界，超限拒绝/降级不失控
4. **删除中断语义**：删除在任意实例生效——本实例解析的文档立即中断；其他实例上的解析由 DB 兜底在检查点（入库每 10 块/向量化每批）秒级停止清理，不产生孤儿数据
5. **总并发核算**：解析并发为"副本数 × parse.concurrency"（默认 2/实例），embedding/Ollama 为共享瓶颈，多副本时需下调单实例并发或扩容推理资源

## 与报表平台集成

生产环境由平台网关做 JWT 鉴权并透传请求（前端调 `/api/ai/*`，见 `web/.env.production`）。
**注意**：
1. 平台网关需额外透传图片路径 `/ai/images/**`（生产开启图片鉴权时，图片 URL 带 HMAC 签名与过期时间，由本服务动态生成）
2. SSE 接口（`/chat`）网关需关闭响应缓冲，否则流式 token 无法实时到达
3. 内部 token `AI_TRUSTED_TOKEN` 由网关注入请求头，前端不携带共享密钥

## 测试与验证

> 当前无单元测试用例（`src/test` 为空，pom 已引入 `spring-boot-starter-test`，可随时补充）。推荐按以下方式验证：

```bash
# 1. 后端健康检查
curl http://localhost:8090/ai/actuator/health          # 期望 {"status":"UP"}

# 2. 前端构建验证
cd web && npm run build

# 3. 检索链路调试（无需重新解析，直接验证召回质量）
curl -X POST http://localhost:8090/api/ai/debug/retrieval \
  -H "Content-Type: application/json" \
  -d '{"question":"如何删除报表"}'                      # 返回分词/关键词/向量/合并/重排分步结果

# 4. 检索量化评估（回放历史问答引用生成评估集 → 批量参数组对比，验证参数改动是好是坏）
curl -X POST http://localhost:8090/api/ai/eval/generate \
  -H "Content-Type: application/json" -d '{"maxCases":100}'   # 生成 data/eval/retrieval-eval.json（问题→期望知识块）
curl -X POST http://localhost:8090/api/ai/eval/run \
  -H "Content-Type: application/json" -d '{
    "kList":[5,10,20],
    "groups":[
      {"name":"当前配置","mode":"normal"},
      {"name":"向量0.7/关键词0.3","mode":"normal","vectorWeight":0.7,"keywordWeight":0.3},
      {"name":"多路合并","mode":"multi"}
    ]}'                                                       # recall@k/MRR/命中率 + 弃用文档断言
# 5. Meilisearch 关键词引擎（可选，替代 MySQL LIKE 全表扫描）
docker compose up meilisearch          # 需先设置 AI_MEILI_KEY（master key，与 app 的 AI_MEILI_KEY 一致）
curl http://localhost:7700/health       # 期望 {"status":"available"}
# 设置页把"关键词引擎"切到 meilisearch（会自动校验服务可用性）→ 保存
curl -X POST http://localhost:8090/api/ai/search-index/reindex   # 首次切换/索引漂移后全量重建（后台执行）
curl http://localhost:8090/api/ai/search-index/stats             # indexedCount 应与 mysqlCount 一致
# 此后解析/编辑/删除会增量同步索引；服务不可用或超时自动降级回 MySQL LIKE，不影响问答
```

**端到端手动验证**（建议每次改动后走一遍）：
1. 文档管理上传含图片 docx → 状态轮询看进度（图片阶段"识别图片 k/total"递增）→ 生效
2. 解析中删除文档 → 后端日志出现"解析已被删除中断"，无孤儿知识块
3. 问答提问 → 深度思考开关（可选）→ 流式回答带引用 `[N]` 角标 → 点击看来源全文与图片 → 👍/👎 反馈
4. 设置页修改任意行为参数（如向量阈值）→ 保存 → 检索调试对比前后召回差异

## 产品化特性

- **安全**：关键密钥零默认值（`DB_PASSWORD`/`AI_TRUSTED_TOKEN` 缺失 fail-fast，模型密钥允许空默认仅功能不可用）、token 恒定时间比较、图片访问 HMAC 签名 URL（`AI_IMAGES_AUTH_ENABLED=true`）、统一异常+参数校验（`@Valid`）、错误信息不泄露内部细节
- **可靠性**：上传失败自动补偿清理（删向量+MySQL+图片）、脏解析记录清理、解析异步化（不阻塞上传）、**解析中删除文档立即中断**（内存标志 + 线程 interrupt + 阶段检查点，清理本次产物）、SSE 异步订阅支持停止生成、查询改写专用线程池（超时隔离 + daemon + PreDestroy 回收）
- **可配置**：模型名/温度/System Prompt 角色段/视觉提示词/检索权重与行为参数/重排区间/解析并发/上下文参数 **数据库存储、保存即生效**（`c_ai_config`，存量升级自动补默认项）；prompt 调整无需重启；检索/重排/解析/问答 4 组共 18 项行为参数收口配置化（原硬编码移除）
- **可观测性**：`/actuator/health` 健康检查、日志级别环境变量化、MyBatis 日志走 slf4j、检索调试 API
- **部署**：multi-stage Dockerfile、docker-compose（含 redis-stack）、nginx 参考配置（`deploy/nginx.conf`，SPA fallback + SSE 关缓冲 + 图片缓存）

## 配置说明

关键配置项（`application.yml`，完整默认值见 [AiAppProperties.java](src/main/java/com/wisesoft/ai/config/AiAppProperties.java)）：

```yaml
ai-app:
  chunk: { max-size: 800, overlap: 100, structural: true, structural-ratio: 0.8 }   # 分块(重叠仅进向量化文本) + 结构感知切分（docx，需重解析生效）
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
    max-width: 1280                         # 识别用压缩图最长边（qwen3-vl 最佳清晰度档，视觉 token 约 1600-2500；展示用原图不受限）
    quality: 0.9                            # JPEG 压缩质量（识别用；带透明通道自动转 PNG）
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
    num-ctx: 16384                         # Ollama 上下文窗口（1280px 图视觉 token 1600-2500，默认 4096 会截断；0=不设置）
  query-rewrite:                           # 查询改写（默认开启）
    enabled: ${AI_QUERY_REWRITE_ENABLED:true}
    timeout-millis: 5000
    # history-rounds / prompt / prompt-multi-turn 为代码默认值（AiAppProperties.QueryRewrite），yml 不覆盖
  system-prompt: "你是\"小报\"..."          # 回答角色段默认值（DB c_ai_config 可覆盖，保存即生效）
  trusted-token: ${AI_TRUSTED_TOKEN}

spring:
  servlet.multipart: { max-file-size: 1024MB, max-request-size: 1100MB }  # 物理上限 1GB；业务上限由 c_ai_config upload.maxFileSize 控制（默认 200MB，设置页可调）
  data.redis: { client-type: jedis, host: ${REDIS_HOST:127.0.0.1}, port: ${REDIS_PORT:6379} }  # 本机环境经 REDIS_PORT=6380 覆盖
  ai.openai:
    api-key: ${AI_DEEPSEEK_KEY}
    base-url: <MaaS 网关 /compatible-mode> # 不含 /v1（Spring AI 自动补）
    chat: { options: { model: qwen3.7-flash-2026-07-15, temperature: 0.3 } }
    embedding: { base-url: ... , options: { model: qwen3.7-text-embedding } }
  ai.vectorstore.redis: { initialize-schema: true, index-name: ai-doc-index, prefix: "ai:chunk:" }  # Spring AI 1.1 起属性为 index-name
```

> **System Prompt 外置边界**：仅"角色与回答风格"段可编辑（设置页）；引用 `[N]` / 图片 `[图片N]` / 追问 `<related>` 规则与后端解析器强耦合，保留代码固定，避免改坏导致解析失效。

## 已知注意事项

- **聊天模型**：当前使用 `qwen3.7-flash-2026-07-15`。该 MaaS 网关对部分模型（如 `qwen-max`）返回 DashScope 原生格式（`{"text":...}`），Spring AI 无法解析（表现为 0 token 无回答）；需使用返回标准 OpenAI 格式的模型（`qwen-plus`、`qwen3.7-flash` 已实测兼容）
- **结构切分/分词升级需重解析**：jieba 分词（关键词路即时生效）与结构感知切分（docx）需对存量文档**重解析**才重建知识块；切分后 `c_ai_message.sources` 的 knowledgeId 失效，**评估集需重新生成**（检索评估页"从历史问答重新生成"）
- **base-url 不含 `/v1`**：Spring AI 与 VisionService 都会自动补 `/v1`；视觉 base-url 以 `/v1` 结尾时也不会重复拼接
- **Spring AI 版本**：1.1.8 起 starter 更名（`spring-ai-starter-model-openai` / `spring-ai-starter-vector-store-redis`），由 `spring-ai-bom` 统一管理；RedisVectorStore 配置属性 `index` → `index-name`，`initialize-schema` 默认 false 需显式开启。**升级 1.1 后旧向量数据建议重新上传/重解析文档**（序列化结构可能变化）
- **图片访问路径**：后端返回 `/ai/images/...`（含 context-path），前端经 `/proxy` 代理时需去掉 `/ai` 前缀（vite 代理 target 已含 context-path），否则双重 `/ai` 404
- **图片描述失败降级**：视觉模型调用失败时图片仍会提取保存，描述降级为 `[图片]` 占位，不影响上传与问答；此时回答图片相关性校验对无描述图自动放行
- **存量库升级**：`c_ai_config.config_value` 需为 TEXT（容纳长 prompt）；**schema 演进自动补列**——启动时 `SchemaMigrator` 解析 schema.sql 与 information_schema 比对，缺失列自动 ALTER 补上（幂等，失败仅告警不阻塞启动；新表 `c_ai_document_version` 仍由启动自动创建）；`c_ai_document.category` 字段保留（前端分类 UI 已移除，存量值已清空）
- **视觉模型思考模式**：qwen3 系列 `max_tokens` 限制会导致内容为空（思考耗尽 token），VisionService 不发送 max_tokens、改用 `think: false`
- **多图描述性能**：图片描述耗时与并发强相关，Ollama 需设 `OLLAMA_NUM_PARALLEL` 才能真正并行；文档重传会重新生成全部图片描述（77 图约 5-10 分钟）

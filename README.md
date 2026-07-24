# DTBD AI Service

报表平台独立 AI 服务，基于 Spring AI 实现 RAG 知识库问答。

## 技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.2.5 + Spring AI 1.0.0-M6 |
| ORM | MyBatis-Plus 3.5.9 |
| 向量库 | Redis Stack (RediSearch) |
| 前端 | Vue 3 + Vite 5 + Ant Design Vue 4 |
| 文档解析 | Apache POI 5.2.3 |

## 目录结构

```
dtbd-ai-service/
├── pom.xml                          # 后端 Maven 项目
├── src/main/java/.../ai/            # 后端源码
│   ├── AiApplication.java           # 入口
│   ├── config/                      # 配置类
│   ├── controller/                  # SSE 聊天 + 文档管理 API
│   ├── service/                     # RAG / 文档 / 会话服务
│   ├── model/                       # 实体
│   ├── mapper/                      # MyBatis Plus Mapper
│   └── dto/                         # 数据传输对象
├── src/main/resources/
│   ├── application.yml              # 配置
│   └── schema.sql                   # 数据库建表脚本
└── web/                             # 前端测试项目
    ├── package.json
    ├── vite.config.js
    ├── index.html
    └── src/
        ├── main.js
        ├── App.vue
        ├── router.js
        ├── api.js
        └── views/
            ├── Chat.vue             # AI 聊天页面
            └── Documents.vue        # 文档管理页面
```

## 启动方式

### 1. 环境准备

**Redis Stack**（CentOS 安装）：
```bash
yum install -y https://packages.redis.io/rpm/redis-stack-server-7.2.0.rpm
systemctl start redis-stack-server
```

**MySQL 数据库**：
```bash
mysql -h 172.168.10.65 -u root -p -e "CREATE DATABASE dtbd_ai CHARACTER SET utf8mb4"
mysql -h 172.168.10.65 -u root -p dtbd_ai < src/main/resources/schema.sql
```

### 2. 配置环境变量

```bash
# DeepSeek 对话 API Key（必填）
export AI_DEEPSEEK_KEY=sk-your-deepseek-api-key

# Embedding API Key（必填，DeepSeek 无 embedding 服务，需另配）
export AI_EMBEDDING_KEY=sk-your-embedding-api-key

# Embedding 服务地址（默认阿里云百炼，可为智谱/硅基流动等）
export AI_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode

# Embedding 模型名（默认 text-embedding-v3）
export AI_EMBEDDING_MODEL=text-embedding-v3

# 可选：Redis 和数据库密码
export REDIS_PASSWORD=wisesoft
export DB_PASSWORD=OceanBase_123#
```

### 3. 启动后端

```bash
# 方式一：直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/dtbd-ai-service-1.0.0-SNAPSHOT.jar
```

后端启动后，API 地址为 `http://localhost:8090/ai/api/ai/*`

### 4. 启动前端测试台

```bash
cd web
npm install
npm run dev
```

前端启动后访问 `http://localhost:5800`

### 5. 使用流程

1. 打开前端页面 → 进入 **文档管理**
2. 上传你的操作手册（.docx 格式），系统自动解析分块并向量化
3. 切换到 **智能问答**，开始提问
4. 如需更新文档，重新上传同名文件会自动替换旧版本

## 与报表平台集成

生产环境使用时，dtbd-core 的 `AiProxyController` 会做 JWT 鉴权并透传请求到本服务，前端仍调原 `/dtbd/api/ai/*` 路径，无需改动。

## 配置说明

关键配置项（`application.yml`）：

```yaml
ai-app:
  chunk:
    max-size: 800          # 知识分块最大字符数
    overlap: 100           # 分块重叠字符数
  retrieval:
    top-k: 5               # 检索返回最相关片段数
    similarity-threshold: 0.5  # 向量相似度阈值
  session:
    max-history: 10        # 保留对话轮数
    expire-minutes: 30     # 会话过期时间
  trusted-token: dtbd-ai-internal-token  # 内部鉴权 token（与 dtbd-core 一致）

spring:
  ai:
    openai:
      api-key: ${AI_DEEPSEEK_KEY}      # 对话用 DeepSeek
      base-url: https://api.deepseek.com
      chat: { options: { model: deepseek-chat, temperature: 0.3 } }
      embedding:
        api-key: ${AI_EMBEDDING_KEY}   # 向量用其他服务
        base-url: ${AI_EMBEDDING_BASE_URL}
        options: { model: ${AI_EMBEDDING_MODEL} }
```
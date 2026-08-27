# DeepRAG Engine

一个基于 Java 17 的 RAG（Retrieval-Augmented Generation，检索增强生成）示例工程。项目围绕“文档入库 → 向量检索 → 大模型生成”的完整流程，按四个阶段逐步演进，从基础 RAG 到高级检索与 Agentic RAG。

> 这是一个可运行的Demo，而不是 Spring Boot 项目。它使用 JDK 内置 `HttpServer` 提供轻量 HTTP API，并依赖本地 Ollama 与 Milvus。

## 功能概览

- 解析 Markdown 与 PDF 文档
- 多种文本分块策略：固定长度、递归、语义、父子分块等
- 使用 Ollama 生成 Embedding 和回答
- 使用 Milvus 存储与检索向量
- Dense / Hybrid 检索、RRF 融合与重排序
- 查询改写、HyDE、多查询和查询拆解
- Naive RAG、Advanced RAG、Self-RAG、CRAG、Adaptive RAG、Agentic RAG
- 交互式 CLI；Stage 1 至 Stage 3 可选启用 HTTP API

## 架构与代码入口

核心数据流如下：

```text
文档 ──> Parser ──> Chunker ──> Embedding ──> Milvus

问题 ──> RAGStrategy ──> Retriever ──> Generator ──> 回答
```

建议按以下顺序阅读：

1. `src/stage1/java/com/deeprag/stage1/Stage1App.java`：默认启动入口与组件装配。
2. `src/main/java/com/deeprag/pipeline/RAGPipeline.java`：文档索引主流程。
3. `src/main/java/com/deeprag/strategy/NaiveRAGStrategy.java`：最小查询闭环。
4. `src/main/java/com/deeprag/{parser,chunker,embedding,store,retriever,generator}`：各环节实现。
5. `src/stage2`、`src/stage3`、`src/stage4`：逐步增加高级 RAG 策略。

## 环境要求

- JDK 17+
- Maven 3.8+
- Docker 与 Docker Compose（用于运行 Milvus）
- [Ollama](https://ollama.com/)

默认配置位于 `config/stage*.yml`，假定服务均运行在本机：

| 服务 | 默认地址 | 用途 |
| --- | --- | --- |
| Ollama | `http://localhost:11434` | 对话模型与 Embedding 模型 |
| Milvus | `localhost:19530` | 向量存储 |
| HTTP API | `http://localhost:8080` | Stage 1 至 Stage 3 的可选接口 |

## 快速开始

### 1. 启动 Milvus

```bash
docker compose -f docker/docker-compose.yml up -d
```

### 2. 准备 Ollama 模型

安装并启动 Ollama 后，下载默认配置所需模型：

```bash
ollama pull qwen2.5
ollama pull nomic-embed-text
```

如使用其他 OpenAI 兼容服务或模型，请修改对应的 `config/stage*.yml`。

### 3. 构建并启动 Stage 1

```bash
mvn -DskipTests package
java -jar target/deeprag-engine-1.0.0-SNAPSHOT.jar
```

默认可执行 Jar 的入口为 `com.deeprag.stage1.Stage1App`。启动后进入交互式命令行。

### 4. 索引并查询文档

在 CLI 中执行：

```text
index testdata/rag-best-practices.md
query 什么是 RAG？
```

常用命令：

| 命令 | 说明 |
| --- | --- |
| `index <文件路径>` | 解析文档、生成向量并写入 Milvus |
| `query <问题>` | 在当前集合中检索并生成回答 |
| `collections` | 查看已创建的集合 |
| `use <集合名>` | 切换当前查询集合 |
| `status` | 查看当前状态 |
| `evaluate` | 运行评测集（仅 Stage 1） |
| `help` | 查看命令帮助 |
| `quit` | 退出程序 |

## 运行其他阶段

各阶段共享核心代码，但使用不同配置和策略。构建后可通过 Maven 运行指定入口：

```bash
# Stage 2：查询理解、混合检索、重排序等
mvn compile exec:java -Dexec.mainClass=com.deeprag.stage2.Stage2App

# Stage 3：Self-RAG、CRAG、Adaptive RAG
mvn compile exec:java -Dexec.mainClass=com.deeprag.stage3.Stage3App

# Stage 4：Agentic RAG
mvn compile exec:java -Dexec.mainClass=com.deeprag.stage4.Stage4App
```

| 阶段 | 重点能力 |
| --- | --- |
| Stage 1 | 基础 Parse → Chunk → Embed → Store → Retrieve → Generate 流程 |
| Stage 2 | 高级分块、查询改写、HyDE、混合检索、重排序、引用与幻觉检测 |
| Stage 3 | Self-RAG、CRAG、Adaptive RAG |
| Stage 4 | Agentic RAG 与多轮动态检索 |

## HTTP API（Stage 1 至 Stage 3）

当配置文件中的 `api.port` 大于 `0` 时，Stage 1、2、3 会在启动 CLI 的同时监听 HTTP API。默认端口为 `8080`。

### 查询

```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"query":"什么是 RAG？","collection":"rag_best_practices"}'
```

### 索引文档

```bash
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"filePath":"testdata/rag-best-practices.md"}'
```

### 查看集合

```bash
curl http://localhost:8080/api/v1/collections
```

> API 的路由在 `src/main/java/com/deeprag/api/RAGApiController.java` 中通过 JDK `HttpServer` 手动注册，并非 Spring MVC 注解。

## 配置说明

每个阶段对应一个配置文件：

- `config/stage1.yml`
- `config/stage2.yml`
- `config/stage3.yml`
- `config/stage4.yml`

最常修改的配置项：

| 配置路径 | 说明 |
| --- | --- |
| `llm.baseUrl` / `llm.model` | OpenAI 兼容对话模型地址与模型名 |
| `embedding.baseUrl` / `embedding.model` | Embedding 服务地址与模型名 |
| `embedding.dimension` | Embedding 维度，必须与模型输出及 Milvus 集合一致 |
| `vectorStore.host` / `vectorStore.port` | Milvus 地址 |
| `chunker.maxSize` / `chunker.overlap` | 文本块大小及重叠量 |
| `retriever.topK` / `retriever.scoreThreshold` | 召回数量和分数过滤阈值 |
| `api.port` | HTTP API 监听端口；设置为 `0` 可关闭（Stage 1 至 Stage 3） |

## 目录结构

```text
config/                         各阶段 YAML 配置
docker/                         Milvus Docker Compose 配置
evaluation/                     评测集与评测结果
src/main/java/com/deeprag/
  api/                          JDK HttpServer 接口与索引适配器
  pipeline/                     文档索引与基础查询编排
  parser/ chunker/              文档解析与文本分块
  embedding/ store/             向量化与 Milvus 存储
  retriever/ generator/         检索与回答生成
  query/ reranker/ strategy/    查询优化、重排序和 RAG 策略
src/stage1...stage4/            各阶段 CLI 启动入口
testdata/                       可用于索引的示例文档
```

## 常见问题

**无法连接 Ollama 或 Milvus**

确认 Ollama 已启动、Milvus 容器正在运行，并检查 YAML 中的地址、端口与本地服务一致。

**Milvus 写入时报向量维度不匹配**

确认 `embedding.dimension` 与 Embedding 模型的实际输出维度相同。修改维度或更换模型后，请使用新的集合名或清理旧集合。

**HTTP 查询找不到集合**

先调用文档索引接口或在 CLI 中执行 `index`。查询 API 的 `collection` 应填写索引接口返回的名称，例如 `rag_best_practices`；`/api/v1/collections` 返回的是带 `deeprag_` 前缀的 Milvus 物理集合名，使用时应去掉此前缀。

## 相关文档

- [配置指南](docs/config-guide.md)
- [技术架构指南](testdata/deeprag-architecture.md)

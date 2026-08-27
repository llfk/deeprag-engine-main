# DeepRAG Engine 配置指南

## 配置文件说明

配置文件位于 `config/stage1.yml`，采用 YAML 格式。

## 配置项

### LLM 配置 (llm)

| 字段 | 说明 | 默认值 |
|------|------|--------|
| baseUrl | OpenAI 兼容 API 地址 | https://api.openai.com/v1 |
| apiKey | API 密钥 | 无（必填） |
| model | 模型名称 | gpt-4o |
| timeout | 超时时间（秒） | 60 |

### Embedding 配置 (embedding)

| 字段 | 说明 | 默认值 |
|------|------|--------|
| baseUrl | Ollama 服务地址 | http://localhost:11434 |
| model | 嵌入模型名称 | nomic-embed-text |
| dimension | 向量维度 | 768 |
| timeout | 超时时间（秒） | 30 |

### 向量存储配置 (vectorStore)

| 字段 | 说明 | 默认值 |
|------|------|--------|
| host | Milvus 服务地址 | localhost |
| port | Milvus 端口 | 19530 |
| collectionPrefix | 集合名前缀 | deeprag_ |

### 分块配置 (chunker)

| 字段 | 说明 | 默认值 |
|------|------|--------|
| strategy | 分块策略 | fixed_size |
| maxSize | 最大块大小（字符数） | 500 |
| overlap | 重叠字符数 | 50 |

### 检索配置 (retriever)

| 字段 | 说明 | 默认值 |
|------|------|--------|
| topK | 返回最相似的K个结果 | 5 |
| scoreThreshold | 相似度阈值 | 0.5 |

### 评测配置 (evaluation)

| 字段 | 说明 | 默认值 |
|------|------|--------|
| datasetPath | 评估数据集路径 | evaluation/eval_set.json |
| reportPath | 报告输出目录 | evaluation/reports |

## 使用说明

### 1. 启动依赖服务

确保以下服务已启动：
- Ollama（嵌入模型）
- Milvus（向量数据库）

### 2. 索引文档

```bash
mvn exec:java -Dexec.mainClass="com.deeprag.stage1.Stage1App" -Dexec.args="index docs/your-doc.md"
```

### 3. 查询

```bash
mvn exec:java -Dexec.mainClass="com.deeprag.stage1.Stage1App" -Dexec.args="query '你的问题'"
```

### 4. 交互模式

```bash
mvn exec:java -Dexec.mainClass="com.deeprag.stage1.Stage1App" -Dexec.args="interactive"
```

### 5. 运行评测

```bash
mvn exec:java -Dexec.mainClass="com.deeprag.stage1.Stage1App" -Dexec.args="eval"
```

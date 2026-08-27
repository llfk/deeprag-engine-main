# Milvus 向量数据库运维手册

## 1. Milvus 基础概念

### 1.1 什么是向量数据库

向量数据库是专门为高维向量数据设计的存储和检索系统。与传统数据库按精确值查询不同，向量数据库通过计算向量之间的相似度（如余弦相似度、内积、欧氏距离）来检索"最相近"的结果。

在 RAG 场景中，文档被切分为文本块后，每个块通过 Embedding 模型转换为一个高维向量（如 768 维），存入向量数据库。查询时，用户问题同样被转换为向量，在数据库中找到最相似的文本块。

### 1.2 Milvus 核心概念

- **Collection（集合）**：类似于关系数据库的表，存储同一类型的向量数据
- **Field（字段）**：集合中的列，包括向量字段（FLOAT_VECTOR）和标量字段（VARCHAR、INT64、JSON）
- **Index（索引）**：加速向量检索的数据结构，常用类型有 IVF_FLAT、HNSW、AUTO
- **Partition（分区）**：集合的子集，可用于按时间或类别分割数据

### 1.3 DeepRAG 使用的集合结构

每个被索引的文档自动创建一个 Milvus 集合，结构如下：

```
Collection: config_guide (从文件 config-guide.md 自动生成)
├── chunk_id    VARCHAR(128)  -- 主键，如 config_guide_0, config_guide_1
├── content     VARCHAR(8192) -- 文本块原始内容
├── embedding   FLOAT_VECTOR(768) -- nomic-embed-text 向量
├── source      VARCHAR(512)  -- 源文件路径
├── chunk_index INT64         -- 块序号（从 0 开始）
└── metadata    JSON          -- 扩展信息（标题、章节等）
```

索引配置：
- 向量索引：AUTO 类型，使用 COSINE 相似度
- 搜索参数：limit=topK, expr=score > scoreThreshold

## 2. 安装与配置

### 2.1 Docker 部署（推荐）

DeepRAG 的 docker-compose.yml 已包含 Milvus 服务：

```yaml
services:
  milvus:
    image: milvusdb/milvus:v2.4-latest
    ports:
      - "19530:19530"   # gRPC 端口（SDK 连接）
      - "9091:9091"     # 健康检查端口
    volumes:
      - milvus_data:/var/lib/milvus
    environment:
      ETCD_ENDPOINTS: etcd:2379
```

启动命令：`docker compose up -d`

### 2.2 连接方式

DeepRAG 通过以下方式连接 Milvus：

- **Java（Milvus SDK v2）**：使用 `io.milvus.v2.client.MilvusClientV2`，连接 `localhost:19530`
- **Go（Milvus REST API）**：使用 HTTP 请求 `http://localhost:19530/v2/vectordb/...`，无需 SDK 依赖

两种方式在功能上完全等价，只是连接协议不同。

### 2.3 常用配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| host | localhost | Milvus 服务地址 |
| port | 19530 | gRPC/REST 端口 |
| collectionPrefix | deeprag_ | 集合名前缀（部分版本不使用） |
| dimension | 768 | 向量维度，必须与 Embedding 模型匹配 |
| indexType | AUTO | 索引类型 |
| metricType | COSINE | 相似度计算方式 |

## 3. 日常运维操作

### 3.1 查看集合列表

通过 DeepRAG API：
```
GET /api/v1/collections
```

返回示例：
```json
{
  "collections": ["config_guide", "rag_best_practices", "deeprag_architecture"]
}
```

### 3.2 监控指标

通过 DeepRAG 状态接口：
```
GET /api/v1/status
```

返回示例：
```json
{
  "status": "running",
  "strategy": "adaptive",
  "metrics": {
    "queryTotal": 156,
    "cacheHitRate": 0.28,
    "queryP50": 320,
    "queryP95": 1800,
    "queryP99": 4200
  }
}
```

关键指标解读：
- **cacheHitRate**：语义缓存命中率，15%-30% 为正常水平
- **queryP95**：95% 的查询在 1.8 秒内完成
- **queryP99**：99% 的查询在 4.2 秒内完成

### 3.3 清理旧集合

当集合不再使用时，应清理以释放存储空间。目前 DeepRAG 未提供删除集合的 API，需要直接通过 Milvus 客户端操作：

```python
from pymilvus import connections, utility
connections.connect(host="localhost", port="19530")
utility.drop_collection("old_collection_name")
```

## 4. 性能调优

### 4.1 索引选择

| 索引类型 | 适用场景 | 特点 |
|----------|----------|------|
| AUTO | 通用 | Milvus 自动选择最优索引 |
| IVF_FLAT | 中等规模（100万以内） | 精度与速度均衡 |
| HNSW | 低延迟要求 | 查询速度最快，内存占用较高 |
| IVF_PQ | 超大规模 | 内存占用最低，精度有损失 |

### 4.2 topK 参数调优

- topK 是每次检索返回的候选结果数量
- 初始建议设为 5-20，然后通过评估指标调整
- topK 过大：检索噪声多，LLM 输入过长
- topK 过小：可能遗漏相关内容
- 使用 Reranker 时，可先设 topK=20，Rerank 后取 top 5

### 4.3 相似度阈值

- scoreThreshold 过滤低于阈值的检索结果
- 推荐起始值：0.3-0.5（宽松，确保召回率）
- 通过评估 HitRate 和 Faithfulness 逐步调高
- 对于精确问答场景，可设为 0.6-0.7

## 5. 故障排查

### 5.1 连接失败

症状：日志显示 "connection refused" 或 "Milvus not reachable"

排查步骤：
1. 检查 Milvus 容器是否运行：`docker ps | grep milvus`
2. 检查端口是否监听：`lsof -i :19530`
3. 检查健康状态：`curl http://localhost:9091/healthz`
4. 查看容器日志：`docker logs milvus-standalone`

### 5.2 检索结果为空

症状：查询返回 0 条结果

排查步骤：
1. 确认集合中有数据：通过 API 查看集合列表
2. 确认 embedding 维度匹配：索引和查询必须使用同一个模型
3. 降低 scoreThreshold 尝试
4. 检查查询向量是否正确生成

### 5.3 内存不足

症状：Milvus 日志显示 OOM（Out of Memory）

解决方案：
1. 增加 Docker 内存限制（建议至少 4GB）
2. 使用 IVF_PQ 索引减少内存占用
3. 减少向量维度（需更换 Embedding 模型）
4. 定期清理不再使用的集合

## 6. 备份与恢复

### 6.1 数据备份

Milvus 数据存储在 Docker Volume 中：
```bash
# 查看 volume 位置
docker volume inspect milvus_data

# 备份
docker run --rm -v milvus_data:/data -v $(pwd):/backup alpine tar czf /backup/milvus_backup.tar.gz -C /data .
```

### 6.2 数据恢复

```bash
# 恢复
docker run --rm -v milvus_data:/data -v $(pwd):/backup alpine tar xzf /backup/milvus_backup.tar.gz -C /data

# 重启 Milvus
docker compose restart milvus
```

注意：恢复后需要等待 Milvus 加载集合到内存（可能需要几分钟），期间查询可能失败。

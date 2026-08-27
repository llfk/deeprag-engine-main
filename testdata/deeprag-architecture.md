# DeepRAG Engine 技术架构指南

## 1. 项目概述

DeepRAG Engine 是一个企业级 RAG（检索增强生成）知识检索引擎，采用 Java 和 Go 双语言实现。项目通过四个阶段的渐进式演进，从最基础的 Naive RAG 发展到支持多种高级策略和完整生产工程能力的系统。

### 1.1 四阶段演进路线

- **Stage 1 — Naive RAG**：基础管线，每个环节只有单一实现，目标是跑通完整的 Parse → Chunk → Embed → Store → Retrieve → Generate → Evaluate 流程。
- **Stage 2 — 全链路优化**：在每个环节引入多种策略选择，包括 5 种分块策略、查询理解引擎、混合检索（Dense + Sparse + RRF 融合）、BGE Reranker 重排序、引用生成和幻觉检测。
- **Stage 3 — 高级策略**：引入三种自适应策略：Self-RAG（自我反省）、CRAG（置信度路由）和 Adaptive RAG（复杂度自适应），让系统根据查询特征自动选择最优处理路径。
- **Stage 4 — 生产工程**：添加 REST API、语义缓存、性能监控（P50/P95/P99）、Docker 一键部署等生产级能力。

## 2. 核心数据流

DeepRAG 的核心是一条标准的 RAG 数据管线，分为离线索引和在线查询两条路径。

### 2.1 离线索引流程

离线索引将原始文档转化为可检索的向量数据，步骤如下：

1. **文档解析（Parse）**：ParserRouter 根据文件扩展名分发到对应解析器。目前支持 Markdown（.md）和 PDF（.pdf）两种格式。Markdown 解析器提取标题层级结构和正文内容；PDF 解析器使用 PDFBox 3.x（Java）或自定义文本提取（Go）来抽取文本。

2. **文本分块（Chunk）**：将长文档切分为适合检索的小段落。Stage 1 使用固定大小分块（FixedSizeChunker，默认 500 字，50 字重叠）。Stage 2 提供 5 种分块策略：
   - FixedSize：固定窗口切分，适合通用场景
   - Recursive：递归字符切分，按段落 → 句子 → 字符逐级拆分
   - Semantic：基于语义相似度的智能切分，使用 Embedding 计算相邻句子相似度，在语义断点处切分
   - ParentChild：父子块策略，小块用于精准检索，大块用于完整上下文生成
   - Structure：基于文档结构（标题、章节）切分，保持语义完整性

3. **向量化（Embed）**：使用 Ollama 部署的 nomic-embed-text 模型，将每个文本块转换为 768 维的 Dense 向量。该模型通过 Ollama 的原生 /api/embed 接口调用，支持批量嵌入以提高吞吐量。

4. **向量存储（Store）**：使用 Milvus 向量数据库存储向量。每个文本块对应一条记录，包含向量字段、原始文本字段和元数据字段。集合名从文件名自动生成，非字母数字字符替换为下划线。

### 2.2 在线查询流程

在线查询从用户问题出发，经过多步处理生成最终答案：

1. **查询理解（Query Understanding）**：Stage 2 引入的查询引擎对用户问题进行多维分析：
   - 意图分类：判断查询类型（FACTUAL/PROCEDURAL/COMPARISON/CHITCHAT）
   - 查询重写：将模糊或口语化的查询改写为更精确的检索词
   - HyDE（Hypothetical Document Embedding）：让 LLM 先生成一个假设性答案，用这个答案的向量去检索，效果通常优于直接用问题向量
   - 多查询扩展：从原始问题生成多个相关查询，分别检索后合并结果
   - 查询分解：将复杂问题拆分为多个子问题，逐个检索和回答

2. **检索（Retrieve）**：Stage 1 使用 Dense Retriever（纯向量相似度检索，COSINE 距离，topK + 阈值过滤）。Stage 2 引入混合检索：
   - Dense 路：基于 nomic-embed-text 向量的语义检索
   - Sparse 路：基于 BM25 算法的关键词匹配
   - RRF 融合：使用倒数排名融合（Reciprocal Rank Fusion，k=60）合并两路结果
   - 动态权重：根据查询长度自动调整 Dense/Sparse 权重比例

3. **重排序（Rerank）**：使用 BGE-Reranker-v2-m3（Cross-Encoder 架构）对检索结果进行精排。Cross-Encoder 同时编码 query 和 document，比 Bi-Encoder（分别编码后计算相似度）更准确但更慢。系统内置优雅降级机制：当 Reranker 不可用时自动跳过，不影响主流程。

4. **生成回答（Generate）**：将检索到的上下文文本与用户问题一起送入 LLM（默认使用 qwen2.5）。Stage 1 使用简单生成（直接拼接上下文+问题）。Stage 2 增强了三个能力：
   - 引用生成：在回答中标注信息来源的具体文本块
   - 幻觉检测：用另一个 LLM 调用来评估生成内容与检索上下文的一致性，输出 0-1 的幻觉分数
   - 上下文压缩：当检索到的文本过长时，先压缩再送入 LLM，避免超出 token 限制

5. **评估（Evaluate）**：使用 LLM-as-Judge 方式，从四个维度评估系统质量：
   - Faithfulness（忠实度）：回答是否忠实于检索到的上下文，有无编造
   - AnswerRelevancy（答案相关性）：回答是否直接回答了用户问题
   - HitRate（命中率）：正确答案是否出现在检索结果中
   - MRR（平均倒数排名）：正确答案在检索结果中的排名位置

## 3. 高级策略详解

### 3.1 Self-RAG（自我反省）

Self-RAG 的核心理念是让系统学会"自我审视"。处理流程如下：

1. 首先判断当前查询是否需要检索（有些问题可以直接由 LLM 回答）
2. 如果需要检索，执行检索后评估结果的相关性
3. 如果相关性不足，自动触发重试机制（最多 maxRetries 次），每次使用不同的查询改写策略
4. 生成回答后，再次评估回答质量

Self-RAG 适合处理不可预测的、开放式的查询场景。

### 3.2 CRAG（置信度路由）

CRAG 通过评估检索结果的置信度来决定后续处理路径：

- **高置信度**（score >= highThreshold，默认 0.8）：直接使用检索结果生成回答
- **中置信度**（mediumThreshold <= score < highThreshold）：对查询进行补充检索或改写后重新检索
- **低置信度**（score < mediumThreshold，默认 0.4）：放弃检索结果，转而使用 Web 搜索或直接让 LLM 回答

CRAG 特别适合知识库覆盖不全面的场景，能有效避免"强行使用不相关检索结果"的问题。

### 3.3 Adaptive RAG（复杂度自适应）

Adaptive RAG 首先评估查询的复杂度，然后选择最匹配的处理路径：

- **简单查询**（SIMPLE）：直接由 LLM 回答，跳过检索流程（如"你好"、"什么是RAG"）
- **中等查询**（MEDIUM）：执行单次检索 + Rerank + 生成（如"DeepRAG支持哪些分块策略"）
- **复杂查询**（COMPLEX）：并行执行多个子查询，结合 Self-RAG 的迭代优化（如"对比 Dense 检索和混合检索的优劣，给出适用场景建议"）

## 4. 向量存储设计

### 4.1 Milvus 集合结构

每个被索引的文档对应一个 Milvus 集合，集合结构如下：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| chunk_id | VARCHAR(128) | 主键，格式为 `{collection}_{index}` |
| content | VARCHAR(8192) | 原始文本内容 |
| embedding | FLOAT_VECTOR(768) | nomic-embed-text 向量 |
| source | VARCHAR(512) | 来源文件路径 |
| chunk_index | INT64 | 块在文档中的顺序索引 |
| metadata | JSON | 扩展元数据（标题、章节等） |

### 4.2 索引配置

- 索引类型：COSINE（余弦相似度）
- 索引算法：AUTO（Milvus 自动选择）
- 搜索参数：topK + scoreThreshold 双重过滤

### 4.3 集合命名规则

集合名从源文件名自动生成：取文件名（不含路径和扩展名），将非字母数字字符替换为下划线，转小写。例如：
- `config-guide.md` → `config_guide`
- `DeepRAG架构.pdf` → `deeprag___`

## 5. 性能监控指标

Stage 4 的 MetricsService 收集以下运行指标：

- **请求延迟**：记录每次查询的处理时间，计算 P50、P95、P99 和平均值
- **缓存指标**：命中率（cache hit rate）、命中次数、未命中次数
- **策略使用统计**：各策略的调用次数分布
- **Token 估算**：基于查询和回答长度粗略估算 LLM token 消耗量

这些指标通过 `/api/v1/status` 接口暴露，可对接 Prometheus + Grafana 进行可视化监控。

## 6. 部署架构

生产环境使用 Docker Compose 一键部署，包含以下服务：

- **DeepRAG Engine**：Java 或 Go 实现的应用服务
- **Milvus**：向量数据库（端口 19530）
- **Ollama**：本地 LLM 推理服务（端口 11434），运行 qwen2.5 和 nomic-embed-text 两个模型
- **Redis**：可选，用于分布式缓存

所有服务的配置通过 `config/stage4.yml` 统一管理。

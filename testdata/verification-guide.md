# DeepRAG Engine 实战验证指南

本指南配合 `testdata/` 目录中的知识库文档和 `evaluation/eval_set.json` 评测集，通过前端页面逐步验证每个 Stage 的功能。

---

## 前置条件

### 1. 启动依赖服务

```bash
# 启动 Milvus + Redis（docker-compose 在项目根目录或 docker/ 目录）
docker compose up -d

# 确认 Ollama 运行并拉取所需模型
ollama pull qwen2.5
ollama pull nomic-embed-text
ollama pull bge-reranker-v2-m3

# 验证模型可用
curl http://localhost:11434/api/tags | grep name
```

**预期输出：** 能看到 `qwen2.5`、`nomic-embed-text`、`bge-reranker-v2-m3` 三个模型名称。

### 2. 验证 Milvus 连通

```bash
curl http://localhost:19530/v2/vectordb/collections/list -X POST -H "Content-Type: application/json" -d '{}'
```

**预期输出：** `{"code":200,"data":[]}` （空集合列表，说明 Milvus 正常运行）

---

## Stage 1 验证：Naive RAG

### 启动服务

**Java：**
```bash
cd deeprag-engine
mvn compile -q
mvn exec:java -Dexec.mainClass="com.deeprag.stage1.Stage1App"
```

**Go：**
```bash
cd deeprag-engine-go
go run cmd/stage1/main.go
```

**终端预期日志：**
```
[INFO] 服务启动在端口 8080
deeprag>
```
看到 `服务启动在端口 8080` 表示 HTTP API 已就绪。

### 打开前端

浏览器访问 `http://localhost:8080/`

**页面预期：**
- 标题栏显示 `DeepRAG Engine Stage 1`
- 右上角绿色圆点（连接正常）
- 左侧有 4 个导航：智能问答、文档索引、系统状态、集合管理

### 步骤 1：索引知识库文档

点击左侧「文档索引」，依次索引 3 个测试文档：

| 操作 | 输入（文件路径） | 预期页面显示 |
|------|------------------|-------------|
| 索引文档 1 | `testdata/deeprag-architecture.md` | `✓ indexed 已索引到集合: deeprag_architecture` |
| 索引文档 2 | `testdata/rag-best-practices.md` | `✓ indexed 已索引到集合: rag_best_practices` |
| 索引文档 3 | `testdata/milvus-operations.md` | `✓ indexed 已索引到集合: milvus_operations` |

**终端预期日志（每次索引）：**
```
[INFO] [API] 收到索引请求: testdata/deeprag-architecture.md
[INFO] 解析文档: testdata/deeprag-architecture.md
[INFO] 分块完成: 共 XX 块
[INFO] 向量化完成: XX/XX
[INFO] 存储完成: 集合 deeprag_architecture
```

> 注意：`testdata/` 路径是相对于运行命令时的目录。如果路径报错，使用绝对路径如 `/Users/xxx/deeprag-engine/testdata/deeprag-architecture.md`

**点击"显示原始响应"可查看完整 JSON 返回。**

### 步骤 2：验证集合

点击左侧「集合管理」，点击「刷新」。

**预期页面显示：** 3 个绿色圆点的集合标签
```
● deeprag_architecture
● rag_best_practices
● milvus_operations
```

### 步骤 3：基础问答测试

点击左侧「智能问答」，逐条输入以下测试问题：

#### 测试 1：事实型查询

| 项目 | 内容 |
|------|------|
| **输入** | `DeepRAG的四个演进阶段分别是什么` |
| **Collection** | `deeprag_architecture` |
| **预期回答包含** | Stage 1 Naive RAG、Stage 2 全链路优化、Stage 3 高级策略、Stage 4 生产工程 |
| **预期 Meta** | 延迟 XXms、检索块数 ≥ 1、策略名（可能为空或 naive） |

#### 测试 2：过程型查询

| 项目 | 内容 |
|------|------|
| **输入** | `Self-RAG的工作流程是怎样的` |
| **Collection** | `deeprag_architecture` |
| **预期回答包含** | 判断是否需要检索、检索后评估相关性、重试机制、生成后评估 |
| **预期引用** | 至少 1 条引用，来自 deeprag-architecture.md |

#### 测试 3：对比型查询

| 项目 | 内容 |
|------|------|
| **输入** | `Dense检索和Sparse检索各有什么优缺点` |
| **Collection** | `rag_best_practices` |
| **预期回答包含** | Dense 擅长语义理解、Sparse 擅长关键词匹配、混合检索 RRF 融合 |

**终端预期日志（每次查询）：**
```
[INFO] [API] 收到查询: DeepRAG的四个演进阶段分别是什么
[INFO] 查询完成，耗时 XXXms
```

### 步骤 4：查看系统状态

点击左侧「系统状态」。

**Stage 1 预期显示：**
- 策略：显示当前策略名（可能为 `naive` 或空）
- 指标卡片：大部分显示 `-`（Stage 1 没有 MetricsService）
- 页面底部提示"显示原始响应"可查看 JSON

> **Stage 1 限制了什么：** 没有 Reranker、没有缓存、没有混合检索、没有查询重写。这是最基本的 RAG 流程：Parse → FixedSize 切块 → Embed → Dense 检索 → 直接生成。

---

## Stage 2 验证：全链路优化

### 切换 Stage

先停止 Stage 1（终端按 `Ctrl+C` 或输入 `quit`），然后启动 Stage 2。

**Java：**
```bash
mvn exec:java -Dexec.mainClass="com.deeprag.stage2.Stage2App"
```

**Go：**
```bash
go run cmd/stage2/main.go
```

**终端预期日志：**
```
[INFO] 服务启动在端口 8080
deeprag-s2>
```

刷新浏览器页面，标题应变为 `Stage 2`。

### 步骤 1：重新索引文档

Stage 2 使用不同的分块策略（parent_child），需要重新索引：

在「文档索引」面板，依次索引 3 个测试文档（同 Stage 1 的步骤）。也可以用不同的集合名来区分——通过 CLI 操作更灵活，但通过页面索引会覆盖同名的集合。

> **Stage 2 改进了什么：** 文档会被 ParentChild 策略切分（小块 128 字检索，大块 500 字生成），查询会经过重写/HyDE/多查询扩展，检索使用混合检索（Dense + Sparse + RRF），结果会经过 BGE Reranker 重排序，生成时包含引用标注和幻觉检测。

### 步骤 2：对比 Stage 1 的回答质量

用相同的问题重新查询，对比 Stage 2 的回答质量：

| 问题 | Collection | 对比 Stage 1 的改进 |
|------|-----------|---------------------|
| `DeepRAG的四个阶段` | `deeprag_architecture` | 回答更完整，引用更精准 |
| `如何选择分块策略` | `rag_best_practices` | 上下文更充分（ParentChild 大块提供更多背景） |
| `Milvus连接失败怎么排查` | `milvus_operations` | 引用来源更多元 |

**预期回答改进：**
- 回答中包含 `[引用 X]` 标注（如果实现了引用生成）
- Meta 中显示幻觉分数（0-1 之间，越接近 1 越好）
- 延迟可能比 Stage 1 长（因为多了重排序、查询改写等步骤）

### 步骤 3：观察终端日志差异

**Stage 2 查询日志更详细：**
```
[INFO] [查询引擎] 原始查询: DeepRAG的四个阶段
[INFO] [查询引擎] 重写后: DeepRAG Engine 四个演进阶段介绍
[INFO] [HyDE] 生成假设文档...
[INFO] [混合检索] Dense路返回 XX 条, Sparse路返回 XX 条
[INFO] [RRF融合] 合并后 XX 条候选
[INFO] [Reranker] 重排序完成
[INFO] 查询完成，耗时 XXXms
```

### 步骤 4：验证 Reranker 降级

可以临时测试 Reranker 不可用的情况（停止 Ollama 的 bge-reranker-v3 模型），系统应优雅降级不影响查询。

---

## Stage 3 验证：高级策略

### 切换 Stage

停止 Stage 2，启动 Stage 3。

**Java：**
```bash
mvn exec:java -Dexec.mainClass="com.deeprag.stage3.Stage3App"
```

**Go：**
```bash
go run cmd/stage3/main.go
```

**终端预期日志：**
```
[INFO] 服务启动在端口 8080
deeprag-s3>
```

刷新浏览器页面，标题应变为 `Stage 3`。

> **Stage 3 新增了什么：** 三种高级策略让系统自己做决策——Self-RAG 自我反省、CRAG 置信度路由、Adaptive RAG 复杂度自适应。默认使用 Adaptive 策略，会根据问题复杂度自动选择处理路径。

### 步骤 1：测试不同复杂度的问题

Adaptive RAG 会自动判断问题复杂度。依次输入以下问题观察不同的处理路径：

#### 简单问题（可能跳过检索）

| 项目 | 内容 |
|------|------|
| **输入** | `你好` 或 `什么是RAG` |
| **Collection** | `deeprag_architecture` |
| **预期** | 系统判断为简单查询，可能直接由 LLM 回答，延迟较短 |

#### 中等问题（单次检索 + Rerank）

| 项目 | 内容 |
|------|------|
| **输入** | `DeepRAG支持哪些分块策略` |
| **Collection** | `deeprag_architecture` |
| **预期** | 执行检索 → Rerank → 生成，回答包含 5 种策略名称 |

#### 复杂问题（多路径处理）

| 项目 | 内容 |
|------|------|
| **输入** | `对比Dense检索和混合检索的优劣，给出适用场景建议` |
| **Collection** | `rag_best_practices` |
| **预期** | 可能触发并行子查询或 Self-RAG 重试，回答更全面 |
| **终端日志** | 可能看到多次检索、重试记录 |

### 步骤 2：测试 Self-RAG 自我反省

输入一个模糊或关联性不强的问题：

| 项目 | 内容 |
|------|------|
| **输入** | `今天天气怎么样` |
| **Collection** | `deeprag_architecture` |
| **预期** | 系统检测到检索结果不相关，可能触发重试或直接告知无法回答 |

---

## Stage 4 验证：生产工程

### 切换 Stage

停止 Stage 3，启动 Stage 4。

**Java：**
```bash
mvn exec:java -Dexec.mainClass="com.deeprag.stage4.Stage4App"
```

**Go：**
```bash
go run cmd/stage4/main.go
```

刷新浏览器页面，标题应变为 `Stage 4`。

> **Stage 4 新增了什么：** 语义缓存（相似问题直接返回缓存结果）、性能监控（P50/P95/P99 延迟）、完整的 REST API、Docker 一键部署。

### 步骤 1：验证语义缓存

连续发送相同或相似的问题：

| 轮次 | 输入 | 预期 |
|------|------|------|
| 第 1 次 | `DeepRAG的四个阶段` | 正常检索 + 生成，fromCache = false |
| 第 2 次 | `DeepRAG的四个阶段`（完全相同） | 缓存命中，fromCache = true，延迟显著降低 |
| 第 3 次 | `DeepRAG有哪些阶段`（语义相似） | 可能缓存命中（取决于相似度阈值 0.95） |

**观察 Meta 信息：**
- `fromCache: true` 表示命中缓存
- 缓存命中的延迟通常 < 100ms（vs 首次查询的 1-5 秒）

### 步骤 2：监控指标验证

点击「系统状态」面板，执行多次查询后观察：

**预期指标仪表盘：**
| 指标 | 预期值 |
|------|--------|
| 总查询数 | ≥ 3 |
| 缓存命中率 | 15%-30%（取决于查询多样性） |
| Token 使用量 | > 0 |
| P50 延迟 | 500ms-2000ms |
| P95 延迟 | P50 的 2-4 倍 |
| 平均延迟 | 介于 P50 和 P95 之间 |

**策略使用分布图：** 应显示一个柱状条，标注 `adaptive` 策略的调用次数。

**开启"自动刷新"后持续查询，观察指标实时变化。**

### 步骤 3：并发查询测试（可选）

在多个浏览器标签页中同时发起查询，观察：
- P95/P99 延迟变化
- 缓存命中率随查询量增加的趋势
- 系统是否稳定响应

---

## 评测集验证

每个 Stage 都可以运行评测集（需要通过 CLI 操作）：

### Stage 1 CLI 评测

启动 Stage 1 后，在终端输入：
```
deeprag> evaluate
```

**终端预期输出：**
```
正在加载评测集: evaluation/eval_set.json
共 15 条评测项

[1/15] eval_001: DeepRAG Engine 的四个演进阶段分别是什么？
  Faithfulness: 0.85
  AnswerRelevancy: 0.90
  HitRate: 1.0
  ...

===== 评测报告 =====
总查询数: 15
平均 Faithfulness:     0.XX
平均 AnswerRelevancy:  0.XX
平均 HitRate:          0.XX
平均 MRR:              0.XX
```

### 各 Stage 评测指标对比

运行完所有 Stage 的评测后，预期趋势：

| 指标 | Stage 1 | Stage 2 | Stage 3 | Stage 4 |
|------|---------|---------|---------|---------|
| Faithfulness | 0.6-0.8 | 0.75-0.9 | 0.8-0.95 | 0.8-0.95 |
| AnswerRelevancy | 0.6-0.8 | 0.7-0.9 | 0.75-0.95 | 0.75-0.95 |
| HitRate | 0.5-0.7 | 0.7-0.9 | 0.75-0.95 | 0.75-0.95 |
| MRR | 0.3-0.5 | 0.5-0.7 | 0.6-0.8 | 0.6-0.8 |
| 平均延迟 | 1-3s | 2-6s | 2-8s | 1-5s（缓存命中 < 0.5s） |

> 以上数值为参考范围，实际结果取决于 LLM 质量、Embedding 模型、文档内容等因素。

---

## 常见问题排查

### Q: 页面右上角显示红色圆点
**原因：** 后端服务未启动或端口被占用
**排查：**
```bash
# 检查端口占用
lsof -i :8080
# 确认服务运行
curl http://localhost:8080/api/v1/status
```

### Q: 索引文档时报错 "unsupported file format"
**原因：** 文件路径错误或文件不是 .md/.pdf 格式
**排查：** 确认文件路径正确，使用 `ls testdata/` 验证文件存在

### Q: 查询返回空结果或"检索不到相关内容"
**原因：** 文档未索引到对应的 Collection，或 Collection 名不匹配
**排查：**
1. 在「集合管理」面板检查集合是否存在
2. 确认查询时 Collection 名与索引时生成的集合名一致
3. 检查 Milvus 中集合是否有数据

### Q: Ollama 请求超时
**原因：** 模型首次加载需要时间，或内存不足
**排查：**
```bash
# 预热模型
curl http://localhost:11434/api/generate -d '{"model":"qwen2.5","prompt":"hi"}'
# 检查资源
ollama ps
```

### Q: 嵌入（Embedding）失败
**原因：** nomic-embed-text 模型未拉取
**排查：**
```bash
ollama pull nomic-embed-text
ollama list | grep nomic
```

---

## 快速验证清单

完整的验证流程约需 30-45 分钟：

- [ ] 依赖服务全部就绪（Ollama 3 个模型 + Milvus）
- [ ] **Stage 1**：索引 3 个文档 → 查询 3 个问题 → 查看状态 → 运行评测
- [ ] **Stage 2**：重新索引 → 同样 3 个问题对比质量 → 观察终端日志差异
- [ ] **Stage 3**：重新索引 → 测试简单/中等/复杂问题 → 观察策略路由
- [ ] **Stage 4**：重新索引 → 验证缓存命中 → 查看监控指标 → 评测对比

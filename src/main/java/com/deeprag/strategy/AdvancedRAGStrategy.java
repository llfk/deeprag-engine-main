package com.deeprag.strategy;

import com.deeprag.config.DeepRagConfig;
import com.deeprag.generator.*;
import com.deeprag.log.ConsoleLog;
import com.deeprag.query.Intent;
import com.deeprag.query.ProcessedQuery;
import com.deeprag.query.QueryEngine;
import com.deeprag.reranker.Reranker;
import com.deeprag.retriever.RetrievalResult;
import com.deeprag.retriever.Retriever;
import com.deeprag.store.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced RAG 策略（Stage 2 - 增强型）
 * <p>
 * 在 Naive RAG 基础上增加了完整的查询理解、检索优化和生成质量控制：
 * <ol>
 *   <li>查询理解：意图分类 → 查询改写 / HyDE / Multi-Query</li>
 *   <li>混合检索 + 重排序（Reranking）</li>
 *   <li>上下文压缩：过滤低分结果，控制 Token 预算</li>
 *   <li>幻觉检测：验证生成内容是否有检索结果支撑</li>
 * </ol>
 */
public class AdvancedRAGStrategy implements RAGStrategy {

    private final Retriever retriever;
    private final Generator generator;
    private final QueryEngine queryEngine;
    private final Reranker reranker;
    private final ContextCompressor contextCompressor;
    private final HallucinationDetector hallucinationDetector;
    private final DeepRagConfig config;

    public AdvancedRAGStrategy(Retriever retriever,
                                Generator generator,
                                QueryEngine queryEngine,
                                Reranker reranker,
                                ContextCompressor contextCompressor,
                                HallucinationDetector hallucinationDetector,
                                DeepRagConfig config) {
        this.retriever = retriever;
        this.generator = generator;
        this.queryEngine = queryEngine;
        this.reranker = reranker;
        this.contextCompressor = contextCompressor;
        this.hallucinationDetector = hallucinationDetector;
        this.config = config;
    }

    @Override
    public String getName() {
        return "Advanced RAG (Stage 2)";
    }

    /**
     * 执行 Advanced RAG 完整流程
     * <p>
     * 流程：Query理解 → 意图分流 → 检索查询选择 → 混合检索 → Reranking → 上下文压缩 → 生成 → 幻觉检测
     */
    @Override
    public GenerationResult execute(String query, String collection) {
        ConsoleLog.header("Advanced RAG 策略执行");

        // 1. Query 理解（意图分类 + 改写 + HyDE + Multi-Query + 分解）
        ProcessedQuery processedQuery = queryEngine != null ? queryEngine.process(query) : null;

        // 2. 根据意图分支：闲聊直接跳过检索
        if (processedQuery != null && processedQuery.getIntent() == Intent.CHITCHAT) {
            ConsoleLog.step("意图识别为闲聊，直接生成回答");
            return generator.generate(query, new RetrievalResult(query, List.of(), 0));
        }

        // 3. 选择最优检索 query（优先 HyDE，其次改写，兜底原始）
        String retrievalQuery = selectRetrievalQuery(processedQuery, query);

        // 4. 混合检索（含 Multi-Query 变体扩展，扩大召回面）
        List<SearchResult> allCandidates = retrieveWithVariants(retrievalQuery, processedQuery, collection);

        // 5. Reranking：用交叉编码器对候选结果精排，取 finalTopK
        int finalTopK = config.getRetriever().getFinalTopK();
        List<SearchResult> reranked = reranker != null
                ? reranker.rerank(query, allCandidates, finalTopK)
                : allCandidates.stream().limit(finalTopK).toList();

        // 6. 上下文压缩：过滤低分结果，控制 Token 预算
        List<SearchResult> compressed = contextCompressor != null
                ? contextCompressor.compress(reranked)
                : reranked;

        // 7. 生成回答
        RetrievalResult retrievalResult = new RetrievalResult(query, compressed, compressed.size());
        GenerationResult result = generator.generate(query, retrievalResult);

        // 8. 幻觉检测：验证生成内容是否被检索结果支撑
        if (hallucinationDetector != null && config.getGenerator() != null
                && config.getGenerator().isEnableHallucinationDetection()) {
            var hallucination = hallucinationDetector.detect(result.getAnswer(), compressed);
            result.setHallucinationScore((float) hallucination.hallucinationRate());
        }

        return result;
    }

    /**
     * 选择用于检索的 query 文本
     * <p>
     * 优先级：HyDE 假设答案 > 改写后的 query > 原始 query。
     * HyDE 生成的文档式文本比短 query 更适合向量检索，因为它的语义空间更接近真实文档。
     */
    private String selectRetrievalQuery(ProcessedQuery processedQuery, String original) {
        if (processedQuery == null) return original;
        if (processedQuery.getHydeAnswer() != null && !processedQuery.getHydeAnswer().isEmpty()) {
            ConsoleLog.step("使用 HyDE 答案作为检索 query");
            return processedQuery.getHydeAnswer();
        }
        if (processedQuery.getRewritten() != null && !processedQuery.getRewritten().isEmpty()) {
            ConsoleLog.step("使用改写后的 query");
            return processedQuery.getRewritten();
        }
        return original;
    }

    /**
     * 使用主 query 和 Multi-Query 变体进行多路检索，然后去重合并
     * <p>
     * Multi-Query 的核心思想：同一个问题可能有多种表述方式，
     * 对每种表述分别检索，可以弥补单一表述的语义偏差，提升召回率。
     * 去重时保留分数更高的那份结果。
     */
    private List<SearchResult> retrieveWithVariants(String retrievalQuery,
                                                      ProcessedQuery processedQuery,
                                                      String collection) {
        List<SearchResult> allResults = new ArrayList<>();

        // 主 query 检索
        RetrievalResult mainResult = retriever.retrieve(retrievalQuery, collection);
        if (mainResult.getResults() != null) {
            allResults.addAll(mainResult.getResults());
        }

        // Multi-Query 变体检索：对每个变体分别检索，汇总候选
        if (processedQuery != null && processedQuery.getVariants() != null) {
            for (String variant : processedQuery.getVariants()) {
                try {
                    RetrievalResult varResult = retriever.retrieve(variant, collection);
                    if (varResult.getResults() != null) {
                        allResults.addAll(varResult.getResults());
                    }
                } catch (Exception e) {
                    ConsoleLog.warn("变体检索失败: " + e.getMessage());
                }
            }
        }

        // 按 chunkId 去重：同一 chunk 出现多次时保留分数更高的
        return allResults.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SearchResult::getChunkId,
                        sr -> sr,
                        (existing, replacement) -> existing.getScore() >= replacement.getScore() ? existing : replacement
                ))
                .values()
                .stream()
                .sorted(java.util.Comparator.comparingDouble(SearchResult::getScore).reversed())
                .toList();
    }
}

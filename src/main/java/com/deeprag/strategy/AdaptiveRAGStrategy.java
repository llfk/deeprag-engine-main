package com.deeprag.strategy;

import com.deeprag.generator.GenerationResult;
import com.deeprag.generator.Generator;
import com.deeprag.log.ConsoleLog;
import com.deeprag.query.QueryEngine;
import com.deeprag.retriever.Retriever;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Adaptive RAG 策略（Stage 3 - 自适应复杂度路由）
 * <p>
 * 根据查询复杂度动态选择处理路径：
 * <ul>
 *   <li>SIMPLE：简单事实查询 → Dense 检索 + 直接生成（最快）</li>
 *   <li>MEDIUM：需理解或多步推理 → Query 理解 + 混合检索（效果与速度平衡）</li>
 *   <li>COMPLEX：多跳推理、对比分析 → Query 分解 + 子问题并行 Self-RAG + 综合生成（最全面）</li>
 * </ul>
 * 复杂度由 LLM 自动评估，实现"因题制宜"的检索增强策略。
 */
public class AdaptiveRAGStrategy implements RAGStrategy {

    private final ChatLanguageModel llm;
    private final Retriever denseRetriever;
    private final Retriever hybridRetriever;
    private final Generator generator;
    private final QueryEngine queryEngine;
    private final SelfRAGStrategy selfRAGStrategy;
    private final boolean parallelSubQueries;
    private final int maxSubQueries;

    public AdaptiveRAGStrategy(ChatLanguageModel llm,
                                Retriever denseRetriever,
                                Retriever hybridRetriever,
                                Generator generator,
                                QueryEngine queryEngine,
                                SelfRAGStrategy selfRAGStrategy,
                                boolean parallelSubQueries,
                                int maxSubQueries) {
        this.llm = llm;
        this.denseRetriever = denseRetriever;
        this.hybridRetriever = hybridRetriever;
        this.generator = generator;
        this.queryEngine = queryEngine;
        this.selfRAGStrategy = selfRAGStrategy;
        this.parallelSubQueries = parallelSubQueries;
        this.maxSubQueries = maxSubQueries;
    }

    @Override
    public String getName() {
        return "Adaptive RAG (Stage 3)";
    }

    /**
     * 执行 Adaptive RAG 流程：评估复杂度 → 路由到对应路径
     */
    @Override
    public GenerationResult execute(String query, String collection) {
        ConsoleLog.header("Adaptive RAG 策略执行");

        // 用 LLM 评估查询复杂度，决定使用哪条处理路径
        Complexity complexity = assessComplexity(query);
        ConsoleLog.step("Query 复杂度: " + complexity);

        return switch (complexity) {
            case SIMPLE -> executeSimple(query, collection);
            case MEDIUM -> executeMedium(query, collection);
            case COMPLEX -> executeComplex(query, collection);
        };
    }

    /** 简单路径：Dense 检索 + 直接生成，追求最快响应 */
    private GenerationResult executeSimple(String query, String collection) {
        ConsoleLog.step("简单路径: Dense 检索 + 直接生成");
        var retrievalResult = denseRetriever.retrieve(query, collection);
        return generator.generate(query, retrievalResult);
    }

    /**
     * 标准路径：Query 理解 + 混合检索 + 生成
     * <p>
     * 通过 QueryEngine 优化检索 query（优先使用 HyDE，其次改写），
     * 然后用混合检索器同时利用语义和关键词匹配。
     */
    private GenerationResult executeMedium(String query, String collection) {
        ConsoleLog.step("标准路径: Query 理解 + 混合检索 + Reranking + 生成");
        String retrievalQuery = query;
        if (queryEngine != null) {
            try {
                var processed = queryEngine.process(query);
                // 优先 HyDE：假设性文档比短 query 更适合向量检索
                if (processed.getHydeAnswer() != null) {
                    retrievalQuery = processed.getHydeAnswer();
                } else if (processed.getRewritten() != null) {
                    retrievalQuery = processed.getRewritten();
                }
            } catch (Exception e) {
                ConsoleLog.warn("Query 理解失败: " + e.getMessage());
            }
        }
        var retrievalResult = hybridRetriever.retrieve(retrievalQuery, collection);
        return generator.generate(query, retrievalResult);
    }

    /**
     * 复杂路径：Query 分解 → 子问题并行/串行 Self-RAG → 综合生成
     * <p>
     * 对多跳推理、对比分析等复杂查询，先分解为多个子问题，
     * 每个子问题独立走 Self-RAG 流程（含检索决策和相关性评估），
     * 最后用 LLM 综合所有子答案生成完整回答。
     */
    private GenerationResult executeComplex(String query, String collection) {
        ConsoleLog.step("复杂路径: Query 分解 + 子问题并行 Self-RAG + 综合生成");

        // 分解子问题
        List<String> subQueries = List.of(query);
        if (queryEngine != null) {
            try {
                subQueries = queryEngine.process(query).getSubQueries();
                if (subQueries == null || subQueries.isEmpty()) {
                    subQueries = List.of(query);
                }
            } catch (Exception e) {
                ConsoleLog.warn("Query 分解失败: " + e.getMessage());
            }
        }

        // 限制子问题数量，防止过多导致延迟过高
        if (subQueries.size() > maxSubQueries) {
            subQueries = subQueries.subList(0, maxSubQueries);
        }

        // 对每个子问题执行 Self-RAG（支持并行或串行）
        List<SubResult> subResults;
        if (parallelSubQueries && subQueries.size() > 1) {
            subResults = executeParallel(subQueries, collection);
        } else {
            subResults = executeSequential(subQueries, collection);
        }

        // 综合所有子答案生成最终回答
        return synthesize(query, subResults);
    }

    /**
     * 并行执行子问题的 Self-RAG 流程
     * <p>
     * 使用 CompletableFuture 实现并行，适用于子问题之间无依赖的场景。
     * 任一子问题失败不影响其他，失败结果会被记录。
     */
    private List<SubResult> executeParallel(List<String> subQueries, String collection) {
        List<CompletableFuture<SubResult>> futures = subQueries.stream()
                .map(sq -> CompletableFuture.supplyAsync(() -> {
                    try {
                        GenerationResult r = selfRAGStrategy.execute(sq, collection);
                        return new SubResult(sq, r.getAnswer(), r.getRetrievedChunks());
                    } catch (Exception e) {
                        ConsoleLog.error("子问题执行失败: " + e.getMessage());
                        return new SubResult(sq, "无法回答: " + e.getMessage(), List.of());
                    }
                }))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    /** 串行执行子问题的 Self-RAG 流程（兜底方案） */
    private List<SubResult> executeSequential(List<String> subQueries, String collection) {
        List<SubResult> results = new ArrayList<>();
        for (String sq : subQueries) {
            try {
                GenerationResult r = selfRAGStrategy.execute(sq, collection);
                results.add(new SubResult(sq, r.getAnswer(), r.getRetrievedChunks()));
            } catch (Exception e) {
                ConsoleLog.error("子问题执行失败: " + e.getMessage());
                results.add(new SubResult(sq, "无法回答: " + e.getMessage(), List.of()));
            }
        }
        return results;
    }

    /**
     * 综合生成：将所有子问题的答案汇总后交给 LLM 生成最终回答
     * <p>
     * 降级策略：若 LLM 综合失败，则简单拼接所有子问题和答案。
     */
    private GenerationResult synthesize(String originalQuery, List<SubResult> subResults) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("基于以下子问题的答案，综合回答原始问题。\n\n");
        prompt.append("原始问题：").append(originalQuery).append("\n\n");

        for (int i = 0; i < subResults.size(); i++) {
            prompt.append(String.format("子问题%d：%s\n答案%d：%s\n\n",
                    i + 1, subResults.get(i).query, i + 1, subResults.get(i).answer));
        }

        prompt.append("请综合以上信息，给出完整、连贯的回答：");

        try {
            String synthesized = llm.generate(prompt.toString());
            // 收集所有子问题检索到的文本块
            var allChunks = subResults.stream()
                    .flatMap(sr -> sr.chunks.stream())
                    .toList();

            return new GenerationResult(synthesized, new ArrayList<>(), -1, allChunks);
        } catch (Exception e) {
            ConsoleLog.error("综合生成失败: " + e.getMessage());
            // 降级：拼接所有子答案作为兜底
            String fallback = subResults.stream()
                    .map(sr -> "Q: " + sr.query + "\nA: " + sr.answer)
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("无法生成综合回答");
            return new GenerationResult(fallback, new ArrayList<>(), -1, List.of());
        }
    }

    /**
     * 用 LLM 评估查询复杂度
     * <p>
     * 出错时默认返回 MEDIUM，避免对简单或复杂查询做出错误路由。
     */
    private Complexity assessComplexity(String query) {
        try {
            String prompt = """
                    请评估以下问题的复杂度：
                    - SIMPLE: 简单事实查询，一个关键词即可检索
                    - MEDIUM: 需要理解或多步推理
                    - COMPLEX: 多跳推理、对比、综合分析

                    问题：%s
                    只输出级别名称。
                    """.formatted(query);
            String response = llm.generate(prompt).trim().toUpperCase();
            if (response.contains("COMPLEX")) return Complexity.COMPLEX;
            if (response.contains("MEDIUM")) return Complexity.MEDIUM;
            return Complexity.SIMPLE;
        } catch (Exception e) {
            return Complexity.MEDIUM;
        }
    }

    private enum Complexity { SIMPLE, MEDIUM, COMPLEX }

    private record SubResult(String query, String answer,
                             List<com.deeprag.store.SearchResult> chunks) {}
}

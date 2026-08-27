package com.deeprag.strategy;

import com.deeprag.generator.GenerationResult;
import com.deeprag.generator.Generator;
import com.deeprag.log.ConsoleLog;
import com.deeprag.query.QueryEngine;
import com.deeprag.retriever.RetrievalResult;
import com.deeprag.retriever.Retriever;
import com.deeprag.store.SearchResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CRAG 策略（Stage 3 - 基于置信度的路由）
 * <p>
 * Corrective RAG 的核心思想：根据检索结果的置信度分数，动态选择不同的处理路径：
 * <ul>
 *   <li>高置信度（≥ highThreshold）：直接生成，检索结果足够可靠</li>
 *   <li>中等置信度：触发补充检索（改写 query 后重新检索），合并两次结果后生成</li>
 *   <li>低置信度（< lowThreshold）：拒答，告知用户未找到相关信息</li>
 * </ul>
 * 论文参考：Corrective Retrieval Augmented Generation
 */
public class CRAGStrategy implements RAGStrategy {

    private final Retriever retriever;
    private final Generator generator;
    private final QueryEngine queryEngine;
    private final double highThreshold;
    private final double lowThreshold;

    public CRAGStrategy(Retriever retriever, Generator generator,
                         QueryEngine queryEngine, double highThreshold, double lowThreshold) {
        this.retriever = retriever;
        this.generator = generator;
        this.queryEngine = queryEngine;
        this.highThreshold = highThreshold;
        this.lowThreshold = lowThreshold;
    }

    @Override
    public String getName() {
        return "CRAG (Stage 3)";
    }

    /**
     * 执行 CRAG 流程：检索 → 置信度评估 → 路由决策
     */
    @Override
    public GenerationResult execute(String query, String collection) {
        ConsoleLog.header("CRAG 策略执行");

        // 第一步：检索
        RetrievalResult retrievalResult = retriever.retrieve(query, collection);

        // 第二步：评估检索结果的置信度（多维综合评分）
        double confidence = evaluateConfidence(retrievalResult);
        ConsoleLog.step("置信度评分: " + String.format("%.2f", confidence));

        // 路由决策：根据置信度选择处理路径
        if (confidence >= highThreshold) {
            // 高置信度 → 检索结果足够可靠，直接生成
            ConsoleLog.step("置信度高 (≥" + highThreshold + ")，直接生成");
            return generator.generate(query, retrievalResult);
        }

        if (confidence >= lowThreshold) {
            // 中等置信度 → 检索结果有一定相关性但不够，触发补充检索
            ConsoleLog.step("置信度中等，触发补充检索");
            RetrievalResult supplemental = supplementalRetrieve(query, collection);
            // 合并两次检索结果（去重，保留高分）
            List<SearchResult> merged = mergeResults(retrievalResult.getResults(), supplemental.getResults());

            return generator.generate(query, new RetrievalResult(query, merged, merged.size()));
        }

        // 低置信度 → 检索结果不可靠，拒答并给出部分参考信息
        ConsoleLog.step("置信度低 (<" + lowThreshold + ")，无法可靠回答");
        String partialInfo = retrievalResult.getResults().stream()
                .limit(2)
                .map(SearchResult::getContent)
                .collect(Collectors.joining("\n"));

        String answer = "抱歉，未能在知识库中找到足够相关的信息来回答您的问题。";
        if (!partialInfo.isEmpty()) {
            answer += "\n\n以下是一些可能相关的信息供参考：\n" + partialInfo;
        }

        return new GenerationResult(answer, new ArrayList<>(), -1, retrievalResult.getResults());
    }

    /**
     * 评估检索结果的置信度（多维综合评分）
     * <p>
     * 三个维度加权求和：
     * <ul>
     *   <li>Top-1 分数（权重 0.4）：最高分结果的相关性</li>
     *   <li>Top-K 平均分数（权重 0.3）：整体检索质量</li>
     *   <li>有效结果比例（权重 0.3）：基于分数断层检测，判断有效结果数占总数的比例</li>
     * </ul>
     */
    private double evaluateConfidence(RetrievalResult result) {
        List<SearchResult> results = result.getResults();
        if (results == null || results.isEmpty()) return 0.0;

        // 维度 1: 最相关结果的分数
        double top1Score = results.get(0).getScore();

        // 维度 2: 前 5 条结果的平均分数
        double avgScore = results.stream()
                .limit(5)
                .mapToDouble(SearchResult::getScore)
                .average()
                .orElse(0.0);

        // 维度 3: 分数断层检测——当相邻结果分数差 > 0.2 时，认为后面的结果不相关
        int effectiveChunks = results.size();
        for (int i = 1; i < results.size(); i++) {
            double gap = results.get(i - 1).getScore() - results.get(i).getScore();
            if (gap > 0.2) {
                effectiveChunks = i;
                break;
            }
        }

        // 综合评分：0.4 * Top-1 + 0.3 * 平均分 + 0.3 * 有效结果比例
        return 0.4 * top1Score + 0.3 * avgScore + 0.3 * ((double) effectiveChunks / results.size());
    }

    /**
     * 补充检索：改写 query 后重新检索
     * <p>
     * 通过 QueryEngine 对原始 query 进行改写，用改写后的 query 重新检索，
     * 以期获得更多相关结果补充到现有候选中。
     */
    private RetrievalResult supplementalRetrieve(String query, String collection) {
        String rewrittenQuery = query;
        if (queryEngine != null) {
            try {
                var processed = queryEngine.process(query);
                if (processed.getRewritten() != null) {
                    rewrittenQuery = processed.getRewritten();
                }
            } catch (Exception e) {
                ConsoleLog.warn("改写失败，使用原始 query 补充检索");
            }
        }
        return retriever.retrieve(rewrittenQuery, collection);
    }

    /**
     * 合并两组检索结果：去重（同一 chunkId 只保留一份），按分数降序排列
     */
    private List<SearchResult> mergeResults(List<SearchResult> a, List<SearchResult> b) {
        // 使用 LinkedHashMap 保持插入顺序，先放 a 再补充 b 中的新结果
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (SearchResult sr : a) merged.put(sr.getChunkId(), sr);
        for (SearchResult sr : b) {
            if (!merged.containsKey(sr.getChunkId())) {
                merged.put(sr.getChunkId(), sr);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed())
                .toList();
    }
}

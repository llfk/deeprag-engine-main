package com.deeprag.retriever;

import com.deeprag.embedding.EmbeddingService;
import com.deeprag.log.ConsoleLog;
import com.deeprag.store.SearchResult;
import com.deeprag.store.VectorStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器实现（Stage 2）
 * <p>
 * 融合稠密检索（Dense）与稀疏/关键词检索（Sparse），使用 RRF（Reciprocal Rank Fusion）
 * 合并两条检索路径的结果，支持动态权重调整。
 * <p>
 * 稠密路径：查询文本 → 向量化 → searchDense → 获取语义相似结果
 * 稀疏路径：基于关键词匹配的简化实现，对稠密检索候选结果计算词频分数
 * RRF 融合：score = weight * Σ(1 / (k + rank))
 */
public class HybridRetriever implements Retriever {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final int topK;
    private final float threshold;
    private final double denseWeight;
    private final double sparseWeight;
    private final boolean dynamicWeight;
    private final int rrfK;

    public HybridRetriever(EmbeddingService embeddingService,
                           VectorStore vectorStore,
                           int topK,
                           float threshold,
                           double denseWeight,
                           double sparseWeight,
                           boolean dynamicWeight,
                           int rrfK) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.threshold = threshold;
        this.denseWeight = denseWeight;
        this.sparseWeight = sparseWeight;
        this.dynamicWeight = dynamicWeight;
        this.rrfK = rrfK;
    }

    @Override
    public RetrievalResult retrieve(String queryText, String collection) {
        ConsoleLog.step("混合检索开始: " + queryText);

        // 计算实际使用的权重（支持动态调整）
        double actualDenseWeight = denseWeight;
        double actualSparseWeight = sparseWeight;
        if (dynamicWeight) {
            double[] weights = computeDynamicWeights(queryText);
            actualDenseWeight = weights[0];
            actualSparseWeight = weights[1];
            ConsoleLog.dim("动态权重: dense=" + String.format("%.2f", actualDenseWeight)
                    + ", sparse=" + String.format("%.2f", actualSparseWeight));
        }

        // 稠密检索路径：查询文本 → 向量化 → searchDense
        float[] queryVector = embeddingService.embed(queryText);
        ConsoleLog.step("查询向量生成完成 (维度=" + queryVector.length + ")");

        // 多取一些候选结果用于稀疏路径打分，最后再截断到 topK
        int candidateK = Math.max(topK * 3, 30);
        List<SearchResult> denseResults = vectorStore.searchDense(collection, queryVector, candidateK, threshold);
        ConsoleLog.step("稠密检索返回 " + denseResults.size() + " 条候选结果");

        // 稀疏检索路径：基于关键词匹配的简化实现
        List<ScoredCandidate> sparseResults = computeSparseScores(queryText, denseResults);
        ConsoleLog.step("稀疏打分完成，对 " + sparseResults.size() + " 条结果计算了关键词分数");

        // RRF 融合
        List<SearchResult> fusedResults = rrfFusion(denseResults, sparseResults,
                actualDenseWeight, actualSparseWeight);
        ConsoleLog.step("RRF 融合完成，最终 " + fusedResults.size() + " 条结果");

        return new RetrievalResult(queryText, fusedResults, fusedResults.size());
    }

    /**
     * 基于关键词匹配计算稀疏分数
     * <p>
     * 将查询文本拆分为关键词，对每个候选结果的内容统计匹配关键词数，
     * 归一化后作为稀疏分数。匹配时忽略大小写。
     */
    private List<ScoredCandidate> computeSparseScores(String queryText, List<SearchResult> candidates) {
        // 提取查询关键词（按空格和标点分词，过滤短词）
        String[] queryWords = queryText.toLowerCase()
                .split("[\\s,.;!?，。；！？、]+");
        List<String> keywords = Arrays.stream(queryWords)
                .filter(w -> w.length() >= 2)
                .distinct()
                .collect(Collectors.toList());

        ConsoleLog.dim("关键词列表: " + keywords);

        List<ScoredCandidate> scored = new ArrayList<>();
        for (SearchResult candidate : candidates) {
            String contentLower = candidate.getContent().toLowerCase();
            int matchCount = 0;
            for (String keyword : keywords) {
                if (contentLower.contains(keyword)) {
                    matchCount++;
                }
            }
            // 归一化：匹配数 / 关键词总数，避免除零
            float sparseScore = keywords.isEmpty() ? 0.0f : (float) matchCount / keywords.size();
            scored.add(new ScoredCandidate(candidate.getChunkId(), sparseScore));
        }

        // 按稀疏分数降序排列
        scored.sort((a, b) -> Float.compare(b.score, a.score));
        return scored;
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合
     * <p>
     * 公式：score = denseWeight * Σ(1/(rrfK + denseRank)) + sparseWeight * Σ(1/(rrfK + sparseRank))
     * <p>
     * 为每个候选结果分别计算其在稠密/稀疏排序中的排名，加权求和后按总分降序排列。
     */
    private List<SearchResult> rrfFusion(List<SearchResult> denseResults,
                                         List<ScoredCandidate> sparseResults,
                                         double denseWeight,
                                         double sparseWeight) {
        // 构建稠密排名映射：chunkId → rank（从 1 开始）
        Map<String, Integer> denseRankMap = new HashMap<>();
        for (int i = 0; i < denseResults.size(); i++) {
            denseRankMap.put(denseResults.get(i).getChunkId(), i + 1);
        }

        // 构建稀疏排名映射：chunkId → rank（从 1 开始）
        Map<String, Integer> sparseRankMap = new HashMap<>();
        for (int i = 0; i < sparseResults.size(); i++) {
            sparseRankMap.put(sparseResults.get(i).chunkId, i + 1);
        }

        // 收集所有候选 chunkId
        Set<String> allChunkIds = new LinkedHashSet<>();
        denseResults.forEach(r -> allChunkIds.add(r.getChunkId()));

        // 构建 chunkId → SearchResult 的映射（用于最终结果构造）
        Map<String, SearchResult> chunkMap = new HashMap<>();
        for (SearchResult r : denseResults) {
            chunkMap.put(r.getChunkId(), r);
        }

        // 计算每个候选的 RRF 融合分数
        List<SearchResult> fusedResults = new ArrayList<>();
        for (String chunkId : allChunkIds) {
            double rrfScore = 0.0;

            // 稠密路径贡献
            Integer denseRank = denseRankMap.get(chunkId);
            if (denseRank != null) {
                rrfScore += denseWeight * (1.0 / (rrfK + denseRank));
            }

            // 稀疏路径贡献
            Integer sparseRank = sparseRankMap.get(chunkId);
            if (sparseRank != null) {
                rrfScore += sparseWeight * (1.0 / (rrfK + sparseRank));
            }

            SearchResult original = chunkMap.get(chunkId);
            // 用融合分数替换原始分数
            fusedResults.add(new SearchResult(
                    original.getChunkId(),
                    original.getContent(),
                    (float) rrfScore,
                    original.getMetadata()
            ));
        }

        // 按融合分数降序排列，截断到 topK
        fusedResults.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        if (fusedResults.size() > topK) {
            fusedResults = fusedResults.subList(0, topK);
        }

        return fusedResults;
    }

    /**
     * 根据查询长度动态调整权重
     * <p>
     * 短查询（<10 字符）偏向关键词匹配（稀疏路径），
     * 长查询（>30 字符）偏向语义理解（稠密路径），
     * 中等长度保持原始配置权重。
     */
    private double[] computeDynamicWeights(String queryText) {
        int len = queryText.length();
        double dWeight = denseWeight;
        double sWeight = sparseWeight;

        if (len < 10) {
            // 短查询偏向稀疏（关键词）
            dWeight = denseWeight * 0.6;
            sWeight = sparseWeight * 1.4;
        } else if (len > 30) {
            // 长查询偏向稠密（语义）
            dWeight = denseWeight * 1.4;
            sWeight = sparseWeight * 0.6;
        }

        // 归一化，使权重之和为 1
        double total = dWeight + sWeight;
        if (total > 0) {
            dWeight /= total;
            sWeight /= total;
        }

        return new double[]{dWeight, sWeight};
    }

    /**
     * 内部辅助类，用于稀疏路径的候选结果打分
     */
    private static class ScoredCandidate {
        final String chunkId;
        final float score;

        ScoredCandidate(String chunkId, float score) {
            this.chunkId = chunkId;
            this.score = score;
        }
    }
}

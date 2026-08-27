package com.deeprag.strategy;

import com.deeprag.generator.GenerationResult;
import com.deeprag.generator.Generator;
import com.deeprag.retriever.Retriever;
import com.deeprag.retriever.RetrievalResult;

/**
 * Naive RAG 策略（Stage 1 - 最简实现）
 * <p>
 * 最基础的 RAG 流程：直接检索 → 直接生成，没有任何查询预处理、重排序或后处理。
 * 适合作为基线（baseline）对比更高级策略的效果提升。
 */
public class NaiveRAGStrategy implements RAGStrategy {

    private final Retriever retriever;
    private final Generator generator;

    public NaiveRAGStrategy(Retriever retriever, Generator generator) {
        this.retriever = retriever;
        this.generator = generator;
    }

    @Override
    public String getName() {
        return "Naive RAG (Stage 1)";
    }

    /**
     * 执行最简 RAG 流程：检索 → 生成，一步到位
     */
    @Override
    public GenerationResult execute(String query, String collection) {
        // 直接用原始 query 检索，不做任何预处理
        RetrievalResult retrievalResult = retriever.retrieve(query, collection);
        // 直接将检索结果交给生成器
        return generator.generate(query, retrievalResult);
    }
}

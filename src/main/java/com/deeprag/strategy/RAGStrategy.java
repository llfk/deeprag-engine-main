package com.deeprag.strategy;

import com.deeprag.generator.GenerationResult;

/**
 * RAG 策略接口（策略模式）
 * <p>
 * 定义了所有 RAG 策略的统一契约。不同阶段的 RAG 实现（Naive / Advanced / Self-RAG / CRAG / Adaptive）
 * 均实现此接口，使得管线可以在运行时灵活切换策略，无需修改调用方代码。
 */
public interface RAGStrategy {

    /**
     * 获取策略名称，用于日志输出和评测报告标识
     */
    String getName();

    /**
     * 执行 RAG 流程：根据查询文本从指定集合中检索并生成回答
     *
     * @param query      用户查询文本
     * @param collection Milvus 向量集合名称
     * @return 包含答案、引用、幻觉分数等的生成结果
     */
    GenerationResult execute(String query, String collection);
}

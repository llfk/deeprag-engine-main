package com.deeprag.generator;

import com.deeprag.retriever.RetrievalResult;

/**
 * 回答生成器接口，定义了基于检索结果生成回答的方法
 */
public interface Generator {

    /**
     * 根据用户查询和检索结果生成回答
     *
     * @param query           用户查询文本
     * @param retrievalResult 检索结果
     * @return 生成结果
     */
    GenerationResult generate(String query, RetrievalResult retrievalResult);
}

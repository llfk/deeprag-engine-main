package com.deeprag.retriever;

/**
 * 检索器接口，定义了知识检索的核心方法
 */
public interface Retriever {

    /**
     * 根据查询文本检索相关文档片段
     *
     * @param queryText  用户查询文本
     * @param collection 目标集合名称
     * @return 检索结果
     */
    RetrievalResult retrieve(String queryText, String collection);
}

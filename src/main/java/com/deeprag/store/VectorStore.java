package com.deeprag.store;

import java.util.List;

/**
 * 向量存储接口，定义了向量数据库的基本操作
 */
public interface VectorStore {

    /**
     * 创建集合
     *
     * @param name      集合名称
     * @param dimension 向量维度
     */
    void createCollection(String name, int dimension);

    /**
     * 插入或更新向量数据
     *
     * @param collection 集合名称
     * @param data       待插入的文本块嵌入列表
     */
    void upsert(String collection, List<ChunkEmbedding> data);

    /**
     * 向量相似度搜索
     *
     * @param collection  集合名称
     * @param queryVector 查询向量
     * @param topK        返回最相似的前K个结果
     * @param threshold   相似度阈值
     * @return 搜索结果列表
     */
    List<SearchResult> searchDense(String collection, float[] queryVector, int topK, float threshold);

    /**
     * 根据来源文件删除向量数据
     *
     * @param collection 集合名称
     * @param source     来源文件路径
     */
    void deleteBySource(String collection, String source);

    /**
     * 删除集合
     *
     * @param name 集合名称
     */
    void dropCollection(String name);

    /**
     * 列出所有集合
     *
     * @return 集合名称列表
     */
    List<String> listCollections();
}

package com.deeprag.api;

import java.util.List;

/**
 * RAG 索引器接口
 * <p>
 * 定义文档索引的入口方法，由 RAGPipeline 适配实现。
 * 将接口放在 api 包中，实现 API 层与管线层的解耦。
 */
public interface RAGIndexer {
    /**
     * 索引文档到向量数据库
     *
     * @param filePath 文档文件路径
     * @return 创建的集合名称
     */
    String index(String filePath);

    /** 列出所有已创建的集合名称 */
    List<String> listCollections();
}

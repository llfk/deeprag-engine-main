package com.deeprag.retriever;

import com.deeprag.config.DeepRagConfig;
import com.deeprag.embedding.EmbeddingService;
import com.deeprag.log.ConsoleLog;
import com.deeprag.store.SearchResult;
import com.deeprag.store.VectorStore;

import java.util.List;

/**
 * 稠密检索器实现
 * <p>
 * 流程：将查询文本转换为向量 -> 在向量数据库中进行相似度搜索 -> 返回检索结果
 */
public class DenseRetriever implements Retriever {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final int topK;
    private final float scoreThreshold;

    public DenseRetriever(EmbeddingService embeddingService,
                          VectorStore vectorStore,
                          int topK,
                          float scoreThreshold) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.scoreThreshold = scoreThreshold;
    }

    public DenseRetriever(EmbeddingService embeddingService,
                          VectorStore vectorStore,
                          DeepRagConfig.RetrieverConfig config) {
        this(embeddingService, vectorStore, config.getTopK(), config.getScoreThreshold());
    }

    @Override
    public RetrievalResult retrieve(String queryText, String collection) {
        ConsoleLog.step("开始检索: " + queryText);

        // 将查询文本向量化
        float[] queryVector = embeddingService.embed(queryText);
        ConsoleLog.step("查询向量生成完成 (维度=" + queryVector.length + ")");

        // 执行向量相似度搜索
        List<SearchResult> results = vectorStore.searchDense(collection, queryVector, topK, scoreThreshold);
        ConsoleLog.step("检索到 " + results.size() + " 条相关结果");

        return new RetrievalResult(queryText, results, results.size());
    }
}

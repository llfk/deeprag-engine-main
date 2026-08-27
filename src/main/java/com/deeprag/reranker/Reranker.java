package com.deeprag.reranker;

import com.deeprag.store.SearchResult;

import java.util.List;

/**
 * 重排序器接口
 * <p>
 * 对检索器返回的候选结果进行精排，利用交叉编码器等模型
 * 对 query-document 对进行更精确的相关性打分。
 */
public interface Reranker {

    /**
     * 对候选结果进行重排序
     *
     * @param query      用户查询文本
     * @param candidates 候选检索结果列表
     * @param topK       返回前 K 个结果
     * @return 按重排序分数降序排列的结果列表
     */
    List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK);
}

package com.deeprag.reranker;

import com.deeprag.store.SearchResult;

import java.util.List;

/**
 * 空操作重排序器
 * <p>
 * 当重排序功能被禁用时使用，直接返回原始候选结果（截断到 topK），
 * 不做任何排序调整。
 */
public class NoOpReranker implements Reranker {

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}

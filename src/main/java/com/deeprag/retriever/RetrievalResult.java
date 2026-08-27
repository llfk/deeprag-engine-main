package com.deeprag.retriever;

import com.deeprag.store.SearchResult;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 检索结果数据类
 */
@Data
@AllArgsConstructor
public class RetrievalResult {
    private String query;
    private List<SearchResult> results;
    private int totalRetrieved;
}

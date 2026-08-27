package com.deeprag.store;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 向量检索结果数据类
 */
@Data
@AllArgsConstructor
public class SearchResult {
    private String chunkId;
    private String content;
    private float score;
    private Map<String, Object> metadata;
}

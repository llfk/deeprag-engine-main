package com.deeprag.query;

import lombok.Data;

import java.util.List;

/**
 * 查询处理结果
 * <p>
 * 封装经过 QueryEngine 处理后的所有输出，包括意图分类、改写、HyDE、多查询变体和子问题分解
 */
@Data
public class ProcessedQuery {

    /** 原始查询文本 */
    private String original;

    /** 查询意图分类 */
    private Intent intent;

    /** 改写后的查询（若启用改写） */
    private String rewritten;

    /** HyDE 假设性答案——用于替代原始查询进行向量检索 */
    private String hydeAnswer;

    /** Multi-Query 变体——同一语义的不同表述，用于扩大检索覆盖面 */
    private List<String> variants;

    /** 分解后的子问题（COMPARISON 类型查询可能被拆解为多个子问题） */
    private List<String> subQueries;

    public ProcessedQuery(String original) {
        this.original = original;
    }
}

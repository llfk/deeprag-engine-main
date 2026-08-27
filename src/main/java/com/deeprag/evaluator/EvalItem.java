package com.deeprag.evaluator;

import lombok.Data;

/**
 * 评测数据条目，对应评估集中的单个测试项
 */
@Data
public class EvalItem {
    /** 唯一标识 */
    private String id;
    /** 测试问题 */
    private String question;
    /** 标准答案 */
    private String groundTruth;
    /** 来源文档 */
    private String sourceDoc;
    /** 来源文本块（可为空） */
    private String sourceChunk;
    /** 问题类型：FACTUAL / PROCEDURAL / COMPARISON */
    private String type;
    /** 所属集合名称 */
    private String collectionName;
}

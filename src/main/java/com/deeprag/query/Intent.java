package com.deeprag.query;

/**
 * 查询意图枚举
 * <p>
 * 用于对用户查询进行分类，以便后续采取不同的检索策略
 */
public enum Intent {
    /** 事实查询：寻找具体的事实、定义、数据等 */
    FACTUAL,
    /** 步骤查询：询问如何完成某件事的操作流程 */
    PROCEDURAL,
    /** 对比查询：比较两个或多个事物的异同 */
    COMPARISON,
    /** 闲聊：不需要检索的日常对话 */
    CHITCHAT
}

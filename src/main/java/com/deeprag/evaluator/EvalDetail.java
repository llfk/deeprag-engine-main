package com.deeprag.evaluator;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单条评测详情，记录每个测试问题的评测结果
 */
@Data
@AllArgsConstructor
public class EvalDetail {
    /** 测试项ID */
    private String id;
    /** 是否命中正确来源 */
    private boolean hit;
    /** 命中排名（未命中时为 -1） */
    private int rank;
    /** 忠实度分数 */
    private float faithfulness;
    /** 相关性分数 */
    private float relevancy;
}

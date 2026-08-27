package com.deeprag.evaluator;

import com.deeprag.log.ConsoleLog;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 评测报告数据类
 * <p>
 * 包含评测策略名称、总问题数、各项指标和详细评测结果
 */
@Data
public class EvaluationReport {
    /** 策略名称 */
    private String strategyName;
    /** 总问题数 */
    private int totalQuestions;
    /** 指标汇总：faithfulness, answerRelevancy, hitRate, mrr */
    private Map<String, Double> metrics;
    /** 详细评测结果 */
    private List<EvalDetail> details;

    public EvaluationReport(String strategyName, int totalQuestions,
                            Map<String, Double> metrics, List<EvalDetail> details) {
        this.strategyName = strategyName;
        this.totalQuestions = totalQuestions;
        this.metrics = metrics;
        this.details = details;
    }

    /**
     * 打印格式化的评测报告到控制台
     */
    public void printReport() {
        ConsoleLog.header("评测报告: " + strategyName);

        System.out.printf("  总问题数: %d%n", totalQuestions);
        ConsoleLog.blank();

        System.out.println("  ┌──────────────────────────┬──────────┐");
        System.out.println("  │ 指标                     │ 分数     │");
        System.out.println("  ├──────────────────────────┼──────────┤");
        System.out.printf("  │ 忠实度 (Faithfulness)    │ %.4f   │%n", metrics.getOrDefault("faithfulness", 0.0));
        System.out.printf("  │ 相关性 (AnswerRelevancy) │ %.4f   │%n", metrics.getOrDefault("answerRelevancy", 0.0));
        System.out.printf("  │ 命中率 (HitRate)         │ %.4f   │%n", metrics.getOrDefault("hitRate", 0.0));
        System.out.printf("  │ MRR                      │ %.4f   │%n", metrics.getOrDefault("mrr", 0.0));
        System.out.println("  └──────────────────────────┴──────────┘");
        ConsoleLog.blank();

        // 打印逐条详情
        ConsoleLog.step("逐条评测详情:");
        for (EvalDetail detail : details) {
            String hitMark = detail.isHit() ? "✓" : "✗";
            System.out.printf("    [%s] ID=%s  命中=%s  排名=%d  忠实度=%.2f  相关性=%.2f%n",
                    hitMark, detail.getId(),
                    detail.isHit() ? "是" : "否",
                    detail.getRank(),
                    detail.getFaithfulness(),
                    detail.getRelevancy());
        }
        ConsoleLog.blank();
    }
}

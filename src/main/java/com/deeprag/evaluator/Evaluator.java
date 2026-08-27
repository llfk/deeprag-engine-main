package com.deeprag.evaluator;

import com.deeprag.generator.GenerationResult;
import com.deeprag.log.ConsoleLog;
import com.deeprag.store.SearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 系统评测器
 * <p>
 * 加载评估数据集，逐条调用 RAG 管线进行评测：
 * 1. 通过管线函数获取生成结果
 * 2. 程序化计算命中率 (HitRate) 和 MRR
 * 3. 使用 LLM-as-Judge 评估忠实度和相关性
 * 4. 汇总生成评测报告
 */
public class Evaluator {

    private static final String JUDGE_PROMPT_TEMPLATE = """
            请评估以下RAG系统的回答质量。

            问题：%s
            参考答案：%s
            检索上下文：%s
            系统回答：%s

            请分别评分(1-5分)：
            1. 忠实度(Faithfulness)：回答是否忠实于检索上下文，未编造信息
            2. 相关性(AnswerRelevancy)：回答是否准确回应了问题

            只输出JSON：{"faithfulness":分数,"relevancy":分数}
            """;

    private static final Pattern JSON_SCORE_PATTERN = Pattern.compile(
            "\\{\\s*\"faithfulness\"\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*\"relevancy\"\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*\\}"
    );

    private final ChatLanguageModel judgeModel;
    private final String datasetPath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Evaluator(ChatLanguageModel judgeModel, String datasetPath) {
        this.judgeModel = judgeModel;
        this.datasetPath = datasetPath;
        ConsoleLog.info("评测器初始化完成 (数据集=" + datasetPath + ")");
    }

    /**
     * 执行评测
     *
     * @param pipelineFn 管线函数，接受 (collection, query)，返回 GenerationResult
     * @return 评测报告
     */
    public EvaluationReport evaluate(BiFunction<String, String, GenerationResult> pipelineFn) {
        return evaluate("RAG-Strategy", pipelineFn);
    }

    public EvaluationReport evaluate(String strategyName,
                                     BiFunction<String, String, GenerationResult> pipelineFn) {
        ConsoleLog.header("开始评测");

        // 加载评估数据集
        List<EvalItem> evalItems = loadEvalSet();
        ConsoleLog.step("加载评估数据集: " + evalItems.size() + " 条");

        List<EvalDetail> details = new ArrayList<>();
        int hitCount = 0;
        double mrrSum = 0.0;
        double faithfulnessSum = 0.0;
        double relevancySum = 0.0;

        for (int i = 0; i < evalItems.size(); i++) {
            EvalItem item = evalItems.get(i);
            ConsoleLog.step(String.format("评测进度: %d/%d [ID=%s]", i + 1, evalItems.size(), item.getId()));

            try {
                // 调用管线
                GenerationResult result = pipelineFn.apply(item.getCollectionName(), item.getQuestion());

                // 程序化检测命中率和排名
                boolean hit = false;
                int rank = -1;

                if (result.getRetrievedChunks() != null) {
                    for (int j = 0; j < result.getRetrievedChunks().size(); j++) {
                        SearchResult sr = result.getRetrievedChunks().get(j);
                        // 检查来源文档是否匹配
                        Object source = sr.getMetadata() != null ? sr.getMetadata().get("source") : null;
                        if (source != null && source.toString().contains(item.getSourceDoc())) {
                            hit = true;
                            rank = j + 1;
                            break;
                        }
                    }
                }

                if (hit) {
                    hitCount++;
                    mrrSum += 1.0 / rank;
                }

                // LLM-as-Judge 评估忠实度和相关性
                float[] scores = judgeAnswer(item, result);
                faithfulnessSum += scores[0];
                relevancySum += scores[1];

                details.add(new EvalDetail(item.getId(), hit, rank, scores[0], scores[1]));

            } catch (Exception e) {
                ConsoleLog.error("评测失败 [ID=" + item.getId() + "]: " + e.getMessage());
                details.add(new EvalDetail(item.getId(), false, -1, 0.0f, 0.0f));
            }
        }

        // 汇总指标
        int total = evalItems.size();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("faithfulness", faithfulnessSum / total);
        metrics.put("answerRelevancy", relevancySum / total);
        metrics.put("hitRate", (double) hitCount / total);
        metrics.put("mrr", mrrSum / total);

        EvaluationReport report = new EvaluationReport(
                strategyName,
                total,
                metrics,
                details
        );

        report.printReport();
        return report;
    }

    /**
     * 使用 LLM-as-Judge 评估单条回答质量
     *
     * @return float[2]: [faithfulness, relevancy]，范围 1.0-5.0
     */
    private float[] judgeAnswer(EvalItem item, GenerationResult result) {
        try {
            // 组装检索上下文
            String context;
            if (result.getRetrievedChunks() != null && !result.getRetrievedChunks().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (SearchResult chunk : result.getRetrievedChunks()) {
                    sb.append(chunk.getContent()).append("\n");
                }
                context = sb.toString().trim();
            } else {
                context = "（无检索结果）";
            }

            String prompt = String.format(JUDGE_PROMPT_TEMPLATE,
                    item.getQuestion(),
                    item.getGroundTruth(),
                    context,
                    result.getAnswer());

            String judgeResponse = judgeModel.generate(prompt);

            // 解析 JSON 格式的评分
            Matcher matcher = JSON_SCORE_PATTERN.matcher(judgeResponse);
            if (matcher.find()) {
                float faithfulness = Float.parseFloat(matcher.group(1));
                float relevancy = Float.parseFloat(matcher.group(2));
                // 归一化到 0-1
                return new float[]{faithfulness / 5.0f, relevancy / 5.0f};
            }

            ConsoleLog.warn("无法解析 Judge 评分: " + judgeResponse);
            return new float[]{0.0f, 0.0f};

        } catch (Exception e) {
            ConsoleLog.error("Judge 评估异常: " + e.getMessage());
            return new float[]{0.0f, 0.0f};
        }
    }

    /**
     * 加载评估数据集 JSON 文件
     */
    private List<EvalItem> loadEvalSet() {
        try {
            return objectMapper.readValue(
                    new File(datasetPath),
                    new TypeReference<List<EvalItem>>() {}
            );
        } catch (IOException e) {
            throw new RuntimeException("加载评估数据集失败: " + datasetPath, e);
        }
    }
}

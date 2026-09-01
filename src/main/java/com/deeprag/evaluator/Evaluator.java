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
            请严格评估以下RAG系统的回答质量（重点：逐句核对，不要放水）。

            问题：%s
            参考答案：%s
            检索上下文：%s
            系统回答：%s

            严格按以下步骤评估：

            【第一步 · 忠实度核对】
            把系统回答拆成若干条断言（每句话一条）。逐条判断：该断言能否在【检索上下文】中找到原文支撑？
            - 若每条断言都有支撑：忠实度高（4~5 分）。
            - 若存在"检索上下文中根本没有、系统却答出来了"的断言（即编造/幻觉），必须重扣（1~2 分）。
            - 注意：参考答案只用于帮助理解题意，绝不能拿参考答案去替检索上下文给系统回答"背书"。

            【第二步 · 相关性核对】
            判断系统回答是否直接回应了问题的核心诉求，而不是泛泛而谈、兜圈子或答非所问。

            【打分标准】
            忠实度(Faithfulness)：
            5 = 系统回答的每条断言都能在检索上下文中找到原文支撑；
            4 = 绝大多数被支撑，仅个别措辞略有出入；
            3 = 约一半有支撑，另一半是概括或与原意略有出入；
            2 = 存在明显编造/幻觉性内容（检索上下文中没有却答出来了）；
            1 = 基本是编造，与检索上下文无关。

            相关性(AnswerRelevancy)：
            5 = 直接、精准地回答了问题核心；
            4 = 回答了核心，但略冗余或不够精炼；
            3 = 部分回答了问题，有偏离；
            2 = 答非所问或明显偏题；
            1 = 与问题无关。

            最后只输出一行JSON（不要包含任何其它字段或多余解释，核对过程只写在你的思考里，不要写进JSON）：
            {"faithfulness":整数,"relevancy":整数}
            """;

    private static final Pattern JSON_SCORE_PATTERN = Pattern.compile(
            "\\{\\s*\"faithfulness\"\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*\"relevancy\"\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*\\}"
    );

    /**
     * 命中判定阈值：一个 chunk 至少命中 MIN_STRONG_HITS 个语义强短语，
     * 且总命中短语数达到 MIN_TOTAL_HITS 才算命中。
     * 目的是避免"裸数字"等弱短语单独命中导致的假阳性。
     */
    private static final int MIN_STRONG_HITS = 1;
    private static final int MIN_TOTAL_HITS = 2;

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

                // 程序化检测命中率和排名：内容级判定（答案关键短语是否被检索到）
                boolean hit = false;
                int rank = -1;

                if (result.getRetrievedChunks() != null) {
                    int r = hitRank(item, result.getRetrievedChunks());
                    if (r > 0) {
                        hit = true;
                        rank = r;
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
     * 获取用于命中判定的答案核心短语。
     * <p>
     * 优先使用人工标注的 keyPhrases；若为空（如负样本或未标注），
     * 则从 groundTruth 中提取关键数字/专有名词作为兜底。
     * 返回空列表表示"无正确答案可匹配"，此时判定为未命中。
     */
    private List<String> goldPhrases(EvalItem item) {
        if (item.getKeyPhrases() != null && !item.getKeyPhrases().isEmpty()) {
            return item.getKeyPhrases();
        }
        return extractFromGroundTruth(item.getGroundTruth());
    }

    /**
     * 计算答案在检索结果中的排名：第一个满足命中规则（见 {@link #countPhraseHits}）的 chunk 的位置（1-based）。
     * <p>
     * 命中规则：chunk 至少要命中 {@link #MIN_STRONG_HITS} 个语义强短语，
     * 并且总命中短语数达到 {@link #MIN_TOTAL_HITS}，避免"裸数字"等弱短语单独命中造成的假阳性。
     *
     * @return 命中时的排名；未命中或无可匹配短语时返回 -1
     */
    private int hitRank(EvalItem item, List<SearchResult> chunks) {
        List<String> phrases = goldPhrases(item);
        if (phrases.isEmpty() || chunks == null) {
            return -1;
        }
        for (int j = 0; j < chunks.size(); j++) {
            int[] hits = countPhraseHits(chunks.get(j).getContent(), phrases);
            if (hits[0] >= MIN_STRONG_HITS && hits[1] >= MIN_TOTAL_HITS) {
                return j + 1;
            }
        }
        return -1;
    }

    /**
     * 统计 chunk 内容命中的答案关键短语数量（忽略大小写，子串匹配）。
     * <p>
     * 将短语划分为"强短语"（语义词）和"弱短语"（裸数字、纯符号、过短词）。
     * 返回 {@code int[2]}：{@code [strongHits, totalHits]}，
     * 即命中强短语数与命中短语总数。
     */
    private int[] countPhraseHits(String content, List<String> phrases) {
        if (content == null) {
            return new int[]{0, 0};
        }
        String c = content.toLowerCase();
        int strongHits = 0;
        int totalHits = 0;
        for (String p : phrases) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (c.contains(p.toLowerCase())) {
                totalHits++;
                if (!isWeakPhrase(p)) {
                    strongHits++;
                }
            }
        }
        return new int[]{strongHits, totalHits};
    }

    /**
     * 判断短语是否为弱短语：裸数字（含小数/百分比）、纯符号或过短词。
     * <p>
     * 弱短语在命中计数时只计入 totalHits，不计入 strongHits，
     * 从而避免某些题目 keyPhrases 里混入数字导致的假命中。
     */
    private boolean isWeakPhrase(String phrase) {
        String p = phrase.trim();
        if (p.isEmpty()) {
            return true;
        }
        // 纯数字（含小数、百分比，如 0.3、0.5、20、80%）
        if (p.matches("\\d+(?:\\.\\d+)?%?")) {
            return true;
        }
        // 过短词（长度 < 2）或纯符号，语义太弱，不计入强短语
        return p.length() < 2;
    }

    /**
     * 从标准答案中兜底提取候选关键短语：数字/百分比、引号与书名号内的短句、英文专有名词。
     */
    private List<String> extractFromGroundTruth(String groundTruth) {
        List<String> phrases = new ArrayList<>();
        if (groundTruth == null || groundTruth.isEmpty()) {
            return phrases;
        }
        // 数字（含百分比）
        Matcher numMatcher = Pattern.compile("\\d+(?:\\.\\d+)?%?").matcher(groundTruth);
        while (numMatcher.find()) {
            phrases.add(numMatcher.group());
        }
        // 引号 / 书名号内的短句
        Matcher quoteMatcher = Pattern.compile("[“\"「『]([^”\"」』]+)[”\"」』]").matcher(groundTruth);
        while (quoteMatcher.find()) {
            phrases.add(quoteMatcher.group(1));
        }
        // 英文专有名词（连续英文，长度>=2，含连字符）
        Matcher enMatcher = Pattern.compile("[A-Za-z][A-Za-z-]{1,}").matcher(groundTruth);
        while (enMatcher.find()) {
            phrases.add(enMatcher.group());
        }
        return phrases;
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

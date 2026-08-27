package com.deeprag.generator;

import com.deeprag.log.ConsoleLog;
import com.deeprag.store.SearchResult;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 幻觉检测器（Stage 2/3）
 * <p>
 * 检测 LLM 生成内容中是否存在"幻觉"（即没有检索上下文支撑的事实陈述）。
 * 采用两步流程：
 * <ol>
 *   <li>用 LLM 将回答拆分为独立的事实陈述（claims）</li>
 *   <li>逐条验证每个 claim 是否有检索上下文的支撑</li>
 * </ol>
 * 最终输出幻觉率 = 无支撑 claims 数 / 总 claims 数
 */
public class HallucinationDetector {

    private static final String SPLIT_PROMPT = """
            请将以下回答拆分为独立的事实陈述列表。
            每个陈述必须是一个完整的事实，不包含推理或推测。

            回答：%s

            输出 JSON 数组格式：["陈述1", "陈述2", ...]
            """;

    private static final String VERIFY_PROMPT = """
            以下事实陈述是否有检索上下文的支撑？

            陈述：%s
            上下文：%s

            只回答 YES 或 NO。
            """;

    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[\"[\\s\\S]*?]");
    private static final Pattern STRING_PATTERN = Pattern.compile("\"([^\"]+)\"");

    private final ChatLanguageModel chatModel;

    public HallucinationDetector(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行幻觉检测
     *
     * @param answer LLM 生成的回答文本
     * @param chunks 检索到的上下文文本块
     * @return 幻觉检测结果，包含所有 claims、幻觉率和无支撑 claims 列表
     */
    public HallucinationResult detect(String answer, List<SearchResult> chunks) {
        if (answer == null || answer.isEmpty() || chunks == null || chunks.isEmpty()) {
            return new HallucinationResult(List.of(), 0.0, List.of());
        }

        try {
            // 第一步：将回答拆分为独立的事实陈述
            List<String> claims = splitClaims(answer);
            if (claims.isEmpty()) {
                return new HallucinationResult(List.of(), 0.0, List.of());
            }

            // 第二步：用前 5 条检索结果作为验证上下文
            String context = chunks.stream()
                    .limit(5)
                    .map(SearchResult::getContent)
                    .reduce((a, b) -> a + "\n---\n" + b)
                    .orElse("");

            // 逐条验证每个 claim 是否被上下文支撑
            int unsupported = 0;
            List<String> unsupportedClaims = new ArrayList<>();
            for (String claim : claims) {
                String response = chatModel.generate(String.format(VERIFY_PROMPT, claim, context)).trim().toUpperCase();
                if (!response.contains("YES")) {
                    unsupported++;
                    unsupportedClaims.add(claim);
                }
            }

            // 计算幻觉率：无支撑 claims 占总 claims 的比例
            double rate = (double) unsupported / claims.size();
            ConsoleLog.step(String.format("幻觉检测: %d 条陈述, %d 条无支撑, 幻觉率=%.2f",
                    claims.size(), unsupported, rate));

            return new HallucinationResult(claims, rate, unsupportedClaims);

        } catch (Exception e) {
            ConsoleLog.error("幻觉检测失败: " + e.getMessage());
            return new HallucinationResult(List.of(), -1.0, List.of());
        }
    }

    /**
     * 用 LLM 将回答拆分为独立的事实陈述（claims）
     * <p>
     * 通过正则表达式从 LLM 返回的 JSON 数组中提取每条 claim 文本
     */
    private List<String> splitClaims(String answer) {
        try {
            String response = chatModel.generate(String.format(SPLIT_PROMPT, answer));
            Matcher arrayMatcher = JSON_ARRAY_PATTERN.matcher(response);
            if (arrayMatcher.find()) {
                String arrayStr = arrayMatcher.group();
                Matcher strMatcher = STRING_PATTERN.matcher(arrayStr);
                List<String> claims = new ArrayList<>();
                while (strMatcher.find()) {
                    claims.add(strMatcher.group(1));
                }
                return claims;
            }
        } catch (Exception e) {
            ConsoleLog.warn("Claims 分割失败: " + e.getMessage());
        }
        return List.of();
    }

    /** 幻觉检测结果：包含所有 claims、幻觉率和无支撑 claims 列表 */
    public record HallucinationResult(List<String> claims, double hallucinationRate, List<String> unsupportedClaims) {}
}

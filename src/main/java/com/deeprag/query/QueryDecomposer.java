package com.deeprag.query;

import com.deeprag.log.ConsoleLog;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询分解器
 * <p>
 * 使用 LLM 将复杂的查询（如对比查询、多维度查询）拆解为多个独立的子问题，
 * 以便分别检索后合并结果，提升检索精度
 */
public class QueryDecomposer {

    private static final String DECOMPOSE_PROMPT = """
            你是一个查询分解专家。请将以下复杂查询拆分为多个独立的子问题。
            要求：
            1. 每个子问题应该可以独立回答
            2. 子问题覆盖原始查询的所有方面
            3. 如果原始查询已经是简单查询，不需要拆分，直接返回即可
            4. 每个子问题一行，不要编号，不要其他说明

            原始查询：%s
            """;

    private final ChatLanguageModel llm;

    public QueryDecomposer(ChatLanguageModel llm) {
        this.llm = llm;
    }

    /**
     * 分解查询为子问题
     *
     * @param query 原始查询文本
     * @return 子问题列表，失败时返回仅包含原始查询的单元素列表
     */
    public List<String> decompose(String query) {
        try {
            String prompt = String.format(DECOMPOSE_PROMPT, query);
            String response = llm.generate(prompt).trim();
            List<String> subQueries = parseSubQueries(response);
            ConsoleLog.step("查询分解完成 (子问题数=" + subQueries.size() + ")");
            return subQueries;
        } catch (Exception e) {
            ConsoleLog.error("查询分解失败: " + e.getMessage());
            return List.of(query);
        }
    }

    /**
     * 解析 LLM 响应中的子问题
     * <p>
     * 兼容多种格式：纯文本每行一个、JSON 数组、带编号列表等
     */
    private List<String> parseSubQueries(String response) {
        List<String> subQueries = new ArrayList<>();

        // 尝试按 JSON 数组解析
        String trimmed = response.trim();
        if (trimmed.startsWith("[")) {
            try {
                // 简单的 JSON 数组解析（不引入外部库）
                String content = trimmed.substring(1, trimmed.length() - 1);
                String[] items = content.split(",");
                for (String item : items) {
                    String cleaned = item.trim()
                            .replaceAll("^\"|\"$", "")
                            .replaceAll("^'|'$", "")
                            .trim();
                    if (!cleaned.isEmpty()) {
                        subQueries.add(cleaned);
                    }
                }
                if (!subQueries.isEmpty()) {
                    return subQueries;
                }
            } catch (Exception ignored) {
                // JSON 解析失败，回退到逐行解析
            }
        }

        // 逐行解析
        String[] lines = response.split("\n");
        for (String line : lines) {
            String cleaned = line.trim()
                    .replaceAll("^\\d+[.、)）]\\s*", "")
                    .replaceAll("^[-*•]\\s*", "")
                    .trim();
            if (!cleaned.isEmpty()) {
                subQueries.add(cleaned);
            }
        }

        return subQueries.isEmpty() ? List.of(response) : subQueries;
    }
}

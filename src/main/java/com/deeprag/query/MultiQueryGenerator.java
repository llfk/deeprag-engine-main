package com.deeprag.query;

import com.deeprag.log.ConsoleLog;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多查询变体生成器
 * <p>
 * 使用 LLM 为原始查询生成 3 个语义相同但表述不同的变体问题，
 * 用于扩大检索的覆盖面，提升召回率
 */
public class MultiQueryGenerator {

    private static final String MULTI_QUERY_PROMPT = """
            你是一个查询扩展专家。请为以下用户查询生成 3 个不同表述的变体问题。
            要求：
            1. 保持与原始查询相同的语义
            2. 使用不同的措辞和关键词
            3. 每个变体一行，不要编号，不要其他说明

            原始查询：%s
            """;

    private final ChatLanguageModel llm;

    public MultiQueryGenerator(ChatLanguageModel llm) {
        this.llm = llm;
    }

    /**
     * 生成查询变体
     *
     * @param query 原始查询文本
     * @return 变体问题列表，失败时返回空列表
     */
    public List<String> generate(String query) {
        try {
            String prompt = String.format(MULTI_QUERY_PROMPT, query);
            String response = llm.generate(prompt).trim();
            List<String> variants = parseVariants(response);
            ConsoleLog.step("Multi-Query 生成完成 (变体数=" + variants.size() + ")");
            return variants;
        } catch (Exception e) {
            ConsoleLog.error("Multi-Query 生成失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析 LLM 响应中的变体问题
     * <p>
     * 兼容多种格式：纯文本每行一个、带编号列表、带横线/星号列表等
     */
    private List<String> parseVariants(String response) {
        List<String> variants = new ArrayList<>();
        String[] lines = response.split("\n");
        for (String line : lines) {
            String cleaned = line.trim()
                    .replaceAll("^\\d+[.、)）]\\s*", "")   // 去掉行首编号: 1. / 1、 / 1) / 1）
                    .replaceAll("^[-*•]\\s*", "")           // 去掉行首列表符号
                    .trim();
            if (!cleaned.isEmpty()) {
                variants.add(cleaned);
            }
        }
        return variants;
    }
}

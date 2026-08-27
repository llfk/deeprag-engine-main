package com.deeprag.query;

import com.deeprag.log.ConsoleLog;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 查询改写器
 * <p>
 * 使用 LLM 对用户查询进行改写，使其更加清晰、更适合检索，同时保留原始意图并补充必要的上下文
 */
public class QueryRewriter {

    private static final String REWRITE_PROMPT = """
            你是一个查询改写专家。请将以下用户查询改写为更适合信息检索的形式。
            要求：
            1. 保留原始查询的核心意图
            2. 补充必要的上下文信息
            3. 使表述更加清晰明确
            4. 适当扩展关键词以提升检索召回率
            5. 只输出改写后的查询，不要输出其他内容

            原始查询：%s
            """;

    private final ChatLanguageModel llm;

    public QueryRewriter(ChatLanguageModel llm) {
        this.llm = llm;
    }

    /**
     * 改写查询
     *
     * @param query 原始查询文本
     * @return 改写后的查询，失败时返回原始查询
     */
    public String rewrite(String query) {
        try {
            String prompt = String.format(REWRITE_PROMPT, query);
            String rewritten = llm.generate(prompt).trim();
            ConsoleLog.step("查询改写完成: " + query + " -> " + rewritten);
            return rewritten;
        } catch (Exception e) {
            ConsoleLog.error("查询改写失败: " + e.getMessage());
            return query;
        }
    }
}

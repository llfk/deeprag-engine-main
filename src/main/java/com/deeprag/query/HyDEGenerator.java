package com.deeprag.query;

import com.deeprag.log.ConsoleLog;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * HyDE（Hypothetical Document Embeddings）生成器
 * <p>
 * 使用 LLM 为查询生成一段假设性的文档片段。
 * 该片段不需要事实正确，但需要具有真实文档的表述风格，
 * 用于替代原始查询作为向量检索的输入，以提升检索的语义匹配质量
 */
public class HyDEGenerator {

    private static final String HYDE_PROMPT = """
            请针对以下问题，写一段假设性的文档片段，这段文档可能包含该问题的答案。
            注意：不需要确保内容完全正确，但需要像真实文档一样使用专业、准确的表述风格。
            只输出文档片段内容，不要输出其他说明。

            问题：%s
            """;

    private final ChatLanguageModel llm;

    public HyDEGenerator(ChatLanguageModel llm) {
        this.llm = llm;
    }

    /**
     * 生成假设性文档片段
     *
     * @param query 用户查询文本
     * @return 假设性文档片段，用于替代查询进行向量检索；失败时返回 null
     */
    public String generate(String query) {
        try {
            String prompt = String.format(HYDE_PROMPT, query);
            String hydeAnswer = llm.generate(prompt).trim();
            ConsoleLog.step("HyDE 生成完成 (长度=" + hydeAnswer.length() + ")");
            return hydeAnswer;
        } catch (Exception e) {
            ConsoleLog.error("HyDE 生成失败: " + e.getMessage());
            return null;
        }
    }
}

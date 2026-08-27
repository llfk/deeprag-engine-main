package com.deeprag.query;

import com.deeprag.log.ConsoleLog;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 查询意图分类器
 * <p>
 * 使用 LLM 对用户查询进行意图分类，将其归入 FACTUAL / PROCEDURAL / COMPARISON / CHITCHAT 四类之一
 */
public class IntentClassifier {

    private static final String CLASSIFY_PROMPT = """
            你是一个查询意图分类器。请将以下用户查询分类为四种意图之一：
            - FACTUAL：事实查询，寻找具体的事实、定义、数据等
            - PROCEDURAL：步骤查询，询问如何完成某件事的操作流程
            - COMPARISON：对比查询，比较两个或多个事物的异同
            - CHITCHAT：闲聊，不需要检索的日常对话

            用户查询：%s

            请只回复意图类别名称（FACTUAL / PROCEDURAL / COMPARISON / CHITCHAT），不要回复其他内容。
            """;

    private final ChatLanguageModel llm;

    public IntentClassifier(ChatLanguageModel llm) {
        this.llm = llm;
    }

    /**
     * 对查询进行意图分类
     *
     * @param query 用户查询文本
     * @return 意图类别，分类失败时默认返回 FACTUAL
     */
    public Intent classify(String query) {
        try {
            String prompt = String.format(CLASSIFY_PROMPT, query);
            String response = llm.generate(prompt).trim().toUpperCase();

            // 从响应中提取意图关键词（兼容各种格式）
            for (Intent intent : Intent.values()) {
                if (response.contains(intent.name())) {
                    ConsoleLog.step("意图分类: " + intent.name());
                    return intent;
                }
            }

            ConsoleLog.warn("无法识别意图，使用默认 FACTUAL (原始响应: " + response + ")");
            return Intent.FACTUAL;
        } catch (Exception e) {
            ConsoleLog.error("意图分类失败: " + e.getMessage());
            return Intent.FACTUAL;
        }
    }
}

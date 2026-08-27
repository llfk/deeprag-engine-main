package com.deeprag.generator;

import com.deeprag.config.DeepRagConfig;
import com.deeprag.log.ConsoleLog;
import com.deeprag.retriever.RetrievalResult;
import com.deeprag.store.SearchResult;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于大语言模型的简单回答生成器
 * <p>
 * 使用 LangChain4j 的 OpenAiChatModel 调用 LLM，
 * 将检索到的上下文片段与用户查询组装成 Prompt，生成最终回答
 */
public class SimpleGenerator implements Generator {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的知识库问答助手。请根据提供的检索上下文来回答用户的问题。
            要求：
            1. 仅基于给定的上下文内容回答，不要编造信息
            2. 如果上下文中没有相关信息，请明确告知用户
            3. 回答要简洁准确，条理清晰
            4. 适当引用上下文中的关键信息
            """;

    private final OpenAiChatModel chatModel;

    public SimpleGenerator(DeepRagConfig.LlmConfig config) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel())
                .timeout(java.time.Duration.ofSeconds(config.getTimeout()))
                .build();
        ConsoleLog.info("LLM 生成器初始化完成 (模型=" + config.getModel() + ")");
    }

    @Override
    public GenerationResult generate(String query, RetrievalResult retrievalResult) {
        ConsoleLog.step("开始生成回答...");

        // 组装上下文文本
        StringBuilder contextBuilder = new StringBuilder();
        List<SearchResult> retrievedChunks = retrievalResult.getResults();

        if (retrievedChunks != null && !retrievedChunks.isEmpty()) {
            contextBuilder.append("【检索上下文】\n");
            for (int i = 0; i < retrievedChunks.size(); i++) {
                SearchResult chunk = retrievedChunks.get(i);
                contextBuilder.append(String.format("[%d] %s\n\n", i + 1, chunk.getContent()));
            }
        } else {
            contextBuilder.append("【未检索到相关上下文】\n");
        }

        // 构建完整 Prompt
        String userPrompt = contextBuilder + "\n【用户问题】\n" + query;

        // 调用 LLM 生成回答
        String answer;
        try {
            answer = chatModel.generate(userPrompt);
        } catch (Exception e) {
            ConsoleLog.error("LLM 调用失败: " + e.getMessage());
            answer = "抱歉，生成回答时出现错误: " + e.getMessage();
        }

        ConsoleLog.step("回答生成完成 (长度=" + answer.length() + ")");

        // Stage 1: citedChunks 为空，hallucinationScore 为 -1
        return new GenerationResult(
                answer,
                new ArrayList<>(),
                -1.0f,
                retrievedChunks != null ? retrievedChunks : new ArrayList<>()
        );
    }
}

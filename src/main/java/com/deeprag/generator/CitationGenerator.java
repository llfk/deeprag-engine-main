package com.deeprag.generator;

import com.deeprag.log.ConsoleLog;
import com.deeprag.retriever.RetrievalResult;
import com.deeprag.store.SearchResult;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带引用标注的回答生成器（Stage 2）
 * <p>
 * 与 SimpleGenerator 不同，此生成器要求 LLM 在回答中标注信息来源编号 [1]、[2] 等，
 * 然后通过正则表达式从回答中提取引用的 chunkId 列表。
 * 引用标注让用户可以追溯答案的依据，提升可信度。
 */
public class CitationGenerator implements Generator {

    private static final String CITATION_PROMPT = """
            你是一个专业的知识库问答助手。请根据提供的参考资料回答用户的问题。

            要求：
            1. 基于参考资料内容尽可能完整、准确地回答问题
            2. 回答中尽量标注信息来源，格式为 [编号]，编号对应下面的参考资料编号
            3. 如果参考资料中没有相关信息，请明确告知
            4. 回答要简洁准确，条理清晰

            参考资料：
            %s

            用户问题：%s
            """;

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final ChatLanguageModel chatModel;

    public CitationGenerator(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public GenerationResult generate(String query, RetrievalResult retrievalResult) {
        ConsoleLog.step("生成引用标注回答...");

        List<SearchResult> chunks = retrievalResult.getResults();
        if (chunks == null) chunks = new ArrayList<>();

        String numberedContext = buildNumberedContext(chunks);
        String prompt = String.format(CITATION_PROMPT, numberedContext, query);

        String answer;
        try {
            answer = chatModel.generate(prompt);
        } catch (Exception e) {
            ConsoleLog.error("引用生成失败: " + e.getMessage());
            answer = "抱歉，生成回答时出现错误。";
        }

        List<String> citedChunks = extractCitations(answer, chunks);

        ConsoleLog.step("引用标注完成 (引用了 " + citedChunks.size() + " 个片段)");
        return new GenerationResult(answer, citedChunks, -1, chunks);
    }

    /** 将检索结果组装为带编号的上下文文本，编号用于 LLM 在回答中引用 */
    private String buildNumberedContext(List<SearchResult> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append(String.format("[%d] %s\n\n", i + 1, chunks.get(i).getContent()));
        }
        return sb.toString();
    }

    /**
     * 从 LLM 回答中提取引用标注（如 [1]、[2]）对应的 chunkId
     * <p>
     * 通过正则匹配找到所有 [数字] 标记，将编号映射回原始检索结果的 chunkId
     */
    private List<String> extractCitations(String answer, List<SearchResult> chunks) {
        List<String> cited = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            try {
                int idx = Integer.parseInt(matcher.group(1)) - 1;
                if (idx >= 0 && idx < chunks.size()) {
                    String chunkId = chunks.get(idx).getChunkId();
                    if (!cited.contains(chunkId)) cited.add(chunkId);
                }
            } catch (NumberFormatException ignored) {}
        }
        return cited;
    }
}

package com.deeprag.strategy;

import com.deeprag.generator.GenerationResult;
import com.deeprag.generator.Generator;
import com.deeprag.log.ConsoleLog;
import com.deeprag.query.QueryEngine;
import com.deeprag.retriever.RetrievalResult;
import com.deeprag.retriever.Retriever;
import com.deeprag.store.SearchResult;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-RAG 策略（Stage 3 - 自反思型）
 * <p>
 * 引入 LLM 自我评估机制，在检索和生成过程中加入三个关键决策节点：
 * <ol>
 *   <li>检索决策（shouldRetrieve）：判断问题是否需要检索知识库</li>
 *   <li>相关性评估（isRelevant）：判断检索结果是否足以回答问题</li>
 *   <li>重试机制：检索不相关时，依次尝试查询改写和 HyDE 重新检索</li>
 * </ol>
 * 论文参考：Self-RAG: Learning to Retrieve, Generate, and Critique through Self-Reflection
 */
public class SelfRAGStrategy implements RAGStrategy {

    private final Retriever retriever;
    private final Generator generator;
    private final ChatLanguageModel llm;
    private final QueryEngine queryEngine;
    private final int maxRetries;

    public SelfRAGStrategy(Retriever retriever, Generator generator,
                            ChatLanguageModel llm, QueryEngine queryEngine, int maxRetries) {
        this.retriever = retriever;
        this.generator = generator;
        this.llm = llm;
        this.queryEngine = queryEngine;
        this.maxRetries = maxRetries;
    }

    @Override
    public String getName() {
        return "Self-RAG (Stage 3)";
    }

    /**
     * 执行 Self-RAG 流程：检索决策 → 检索 → 相关性评估 → 重试/生成
     */
    @Override
    public GenerationResult execute(String query, String collection) {
        ConsoleLog.header("Self-RAG 策略执行");

        // 节点 1: 检索决策——用 LLM 判断是否需要检索知识库
        if (!shouldRetrieve(query)) {
            ConsoleLog.step("检索决策: 不需要检索，直接生成");
            return generator.generate(query, new RetrievalResult(query, List.of(), 0));
        }

        ConsoleLog.step("检索决策: 需要检索");

        // 执行首次检索
        String retrievalQuery = query;
        RetrievalResult retrievalResult = retriever.retrieve(retrievalQuery, collection);

        // 节点 2: 检索结果评估 + 重试循环
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (isRelevant(query, retrievalResult)) {
                ConsoleLog.step("检索结果评估: 相关 (第 " + (attempt + 1) + " 次尝试)");
                return generator.generate(query, retrievalResult);
            }

            ConsoleLog.step("检索结果评估: 不相关 (第 " + (attempt + 1) + " 次尝试)");

            if (attempt < maxRetries) {
                // 渐进式重试策略：第一次尝试改写 query，后续尝试 HyDE
                if (attempt == 0 && queryEngine != null) {
                    String rewritten = queryEngine.process(query).getRewritten();
                    if (rewritten != null && !rewritten.isEmpty()) {
                        retrievalQuery = rewritten;
                        ConsoleLog.step("重试策略: 使用改写 query");
                    }
                } else {
                    // HyDE：生成假设性答案作为检索 query
                    String hypothetical = generateHypothetical(query);
                    if (hypothetical != null) {
                        retrievalQuery = hypothetical;
                        ConsoleLog.step("重试策略: 使用 HyDE 假设答案");
                    }
                }
                retrievalResult = retriever.retrieve(retrievalQuery, collection);
            }
        }

        // 重试耗尽，用最后一次检索结果生成（兜底）
        ConsoleLog.step("重试耗尽，使用已有结果生成");
        return generator.generate(query, retrievalResult);
    }

    /**
     * 检索决策：用 LLM 判断问题是否需要检索知识库
     * <p>
     * 常识问题、闲聊、简单计算等不需要检索；关于公司政策、技术文档等需要检索。
     * 出错时默认需要检索（宁可多检索，不可漏检索）。
     */
    private boolean shouldRetrieve(String query) {
        try {
            String prompt = """
                    判断以下问题是否需要检索知识库才能回答。
                    常识问题、闲聊、简单计算等不需要检索。
                    关于公司政策、技术文档、业务流程的问题需要检索。

                    问题：%s
                    只回答 YES 或 NO。
                    """.formatted(query);
            String response = llm.generate(prompt).trim().toUpperCase();
            return response.contains("YES");
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 相关性评估：用 LLM 判断检索结果是否足以回答用户问题
     * <p>
     * 取前 3 条检索结果，让 LLM 判断 RELEVANT / IRRELEVANT。
     * 出错时默认认为相关（避免不必要的重试）。
     */
    private boolean isRelevant(String query, RetrievalResult result) {
        if (result.getResults() == null || result.getResults().isEmpty()) return false;
        try {
            String chunks = result.getResults().stream()
                    .limit(3)
                    .map(SearchResult::getContent)
                    .reduce((a, b) -> a + "\n---\n" + b)
                    .orElse("");
            String prompt = """
                    以下检索结果是否足以回答用户的问题？

                    问题：%s
                    检索结果：%s

                    只回答 RELEVANT 或 IRRELEVANT。
                    """.formatted(query, chunks);
            String response = llm.generate(prompt).trim().toUpperCase();
            return response.contains("RELEVANT");
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 生成假设性文档片段（HyDE 内联实现）
     * <p>
     * 不需要正确，只需要像真实文档一样的表述风格，用于替代原始 query 做向量检索。
     * 原理：假设性文档的语义空间比短 query 更接近真实文档，能提升检索的召回质量。
     */
    private String generateHypothetical(String query) {
        try {
            String prompt = """
                    请根据以下问题，写一段可能包含答案的文档片段。
                    不需要正确，只需要写得像真实文档。

                    问题：%s
                    文档片段：""".formatted(query);
            return llm.generate(prompt).trim();
        } catch (Exception e) {
            return null;
        }
    }
}

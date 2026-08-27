package com.deeprag.strategy;

import com.deeprag.generator.GenerationResult;
import com.deeprag.generator.Generator;
import com.deeprag.log.ConsoleLog;
import com.deeprag.query.QueryEngine;
import com.deeprag.retriever.RetrievalResult;
import com.deeprag.retriever.Retriever;
import com.deeprag.store.SearchResult;
import com.deeprag.store.VectorStore;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agentic RAG 策略（Stage 4 - Agent 化动态检索）
 * <p>
 * 将 RAG 嵌入 Agent 循环，LLM 自主决定每一步的动作：
 * <ul>
 *   <li>是否需要再检索一次？用什么关键词？</li>
 *   <li>当前信息是否足够回答？</li>
 *   <li>信息够了就生成最终答案</li>
 * </ul>
 * 与 Adaptive RAG 的区别：Adaptive 是"一次决策一次检索"，Agentic 是"多轮动态检索"。
 */
public class AgenticRAGStrategy implements RAGStrategy {

    private final ChatLanguageModel llm;
    private final Retriever denseRetriever;
    private final Retriever hybridRetriever;
    private final Generator generator;
    private final QueryEngine queryEngine;
    private final VectorStore vectorStore;
    private final String collectionPrefix;
    private final int maxIterations;

    public AgenticRAGStrategy(ChatLanguageModel llm,
                               Retriever denseRetriever,
                               Retriever hybridRetriever,
                               Generator generator,
                               QueryEngine queryEngine,
                               VectorStore vectorStore,
                               String collectionPrefix,
                               int maxIterations) {
        this.llm = llm;
        this.denseRetriever = denseRetriever;
        this.hybridRetriever = hybridRetriever;
        this.generator = generator;
        this.queryEngine = queryEngine;
        this.vectorStore = vectorStore;
        this.collectionPrefix = collectionPrefix;
        this.maxIterations = maxIterations;
    }

    @Override
    public String getName() {
        return "Agentic RAG (Stage 4)";
    }

    @Override
    public GenerationResult execute(String query, String collection) {
        ConsoleLog.header("Agentic RAG 策略执行");
        ConsoleLog.step("原始问题: " + query);

        // 累积上下文：所有轮次检索到的文本块
        List<SearchResult> allChunks = new ArrayList<>();
        // 已使用过的检索 query，避免重复
        Set<String> usedQueries = new HashSet<>();
        usedQueries.add(query.toLowerCase().trim());

        // 初始检索：跨集合检索所有集合，合并去重
        String currentQuery = query;
        List<SearchResult> initialChunks = retrieveAllCollections(currentQuery, collection);
        addNewChunks(allChunks, initialChunks);
        ConsoleLog.step("第 1 轮检索完成，已收集 " + allChunks.size() + " 个文本块");

        // Agent 循环
        for (int i = 1; i <= maxIterations; i++) {
            // 让 LLM 判断当前信息是否足够，以及下一步应该怎么做
            AgentDecision decision = decide(query, allChunks, i);

            ConsoleLog.step("第 " + i + " 轮 Agent 决策: " + decision.action
                    + (decision.newQuery != null ? " | 新query: " + decision.newQuery : ""));

            switch (decision.action) {
                case GENERATE -> {
                    ConsoleLog.step("信息充分，生成最终答案");
                    return generateAnswer(query, allChunks);
                }
                case RETRIEVE -> {
                    String retrieveQuery = decision.newQuery();
                    if (retrieveQuery == null || retrieveQuery.isBlank()) {
                        ConsoleLog.warn("Agent 返回空 query，尝试改写");
                        retrieveQuery = tryRewrite(query);
                    }
                    if (retrieveQuery != null
                            && !usedQueries.contains(retrieveQuery.toLowerCase().trim())) {
                        usedQueries.add(retrieveQuery.toLowerCase().trim());
                        List<SearchResult> newChunks = retrieveAllCollections(retrieveQuery, collection);
                        addNewChunks(allChunks, newChunks);
                        ConsoleLog.step("检索到 " + newChunks.size() + " 个新文本块，累计 "
                                + allChunks.size() + " 个");
                    } else {
                        ConsoleLog.dim("query 已使用过或为空，跳过本轮检索");
                    }
                }
                case REWRITE_AND_RETRIEVE -> {
                    String rewritten = tryRewrite(query);
                    if (rewritten != null && !usedQueries.contains(rewritten.toLowerCase().trim())) {
                        usedQueries.add(rewritten.toLowerCase().trim());
                        List<SearchResult> newChunks = retrieveAllCollections(rewritten, collection);
                        addNewChunks(allChunks, newChunks);
                        ConsoleLog.step("改写后检索到 " + newChunks.size() + " 个新文本块");
                    }
                }
            }
        }

        // 达到最大迭代次数，用已有信息生成答案
        ConsoleLog.step("达到最大迭代次数 (" + maxIterations + ")，生成最终答案");
        return generateAnswer(query, allChunks);
    }

    /**
     * Agent 决策：让 LLM 判断当前上下文是否足够，以及下一步动作
     */
    private AgentDecision decide(String originalQuery, List<SearchResult> chunks, int iteration) {
        String contextSummary = chunks.stream()
                .limit(10)
                .map(c -> c.getContent().length() > 200
                        ? c.getContent().substring(0, 200) + "..."
                        : c.getContent())
                .collect(Collectors.joining("\n---\n"));

        String prompt = """
                你是一个智能检索助手。根据原始问题和当前已收集的信息，决定下一步动作。

                原始问题：%s
                当前迭代轮次：%d

                已收集的信息摘要：
                %s

                请从以下动作中选择一个：
                - GENERATE: 当前信息已经足够回答原始问题
                - RETRIEVE: 需要更多检索，请在 new_query 中提供新的检索关键词或问题
                - REWRITE_AND_RETRIEVE: 需要改写原始问题后重新检索

                请用以下 JSON 格式回复（不要包含其他内容）：
                {"action": "GENERATE"} 或
                {"action": "RETRIEVE", "new_query": "新的检索问题"}
                {"action": "REWRITE_AND_RETRIEVE"}
                """.formatted(originalQuery, iteration, contextSummary);

        try {
            String response = llm.generate(prompt).trim();
            return parseDecision(response);
        } catch (Exception e) {
            ConsoleLog.warn("Agent 决策失败: " + e.getMessage() + "，默认生成答案");
            return new AgentDecision(Action.GENERATE, null);
        }
    }

    private AgentDecision parseDecision(String response) {
        // 提取 JSON 部分
        String json = response;
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = response.substring(start, end + 1);
        }

        String actionStr = extractJsonValue(json, "action");
        String newQuery = extractJsonValue(json, "new_query");

        Action action = switch (actionStr.toUpperCase().trim()) {
            case "RETRIEVE" -> Action.RETRIEVE;
            case "REWRITE_AND_RETRIEVE" -> Action.REWRITE_AND_RETRIEVE;
            default -> Action.GENERATE;
        };

        return new AgentDecision(action, newQuery);
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        int valueStart = json.indexOf('"', colonIdx + 1);
        if (valueStart < 0) return null;
        int valueEnd = json.indexOf('"', valueStart + 1);
        if (valueEnd < 0) return null;
        return json.substring(valueStart + 1, valueEnd);
    }

    /**
     * 尝试通过 QueryEngine 改写 query
     */
    private String tryRewrite(String query) {
        if (queryEngine == null) return null;
        try {
            var processed = queryEngine.process(query);
            if (processed.getRewritten() != null) return processed.getRewritten();
            if (processed.getHydeAnswer() != null) return processed.getHydeAnswer();
        } catch (Exception e) {
            ConsoleLog.warn("Query 改写失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 去重添加新文本块（按 content 相似度去重）
     */
    private void addNewChunks(List<SearchResult> allChunks, List<SearchResult> newChunks) {
        Set<String> existingContents = allChunks.stream()
                .map(c -> c.getContent().trim())
                .collect(Collectors.toSet());

        for (SearchResult chunk : newChunks) {
            if (!existingContents.contains(chunk.getContent().trim())) {
                allChunks.add(chunk);
                existingContents.add(chunk.getContent().trim());
            }
        }
    }

    /**
     * 跨集合检索：遍历所有集合检索，按内容去重后合并结果。
     * 与其他策略（单集合检索）的关键区别就在这里——Agentic 每轮都搜索所有集合，
     * 把多个来源的信息融合进上下文。若 vectorStore 为空则降级为单集合检索（向后兼容）。
     */
    private List<SearchResult> retrieveAllCollections(String query, String collection) {
        if (vectorStore == null) {
            // 降级：搜单个集合
            return hybridRetriever.retrieve(query, collection).getResults();
        }

        List<String> cols;
        try {
            cols = vectorStore.listCollections();
        } catch (Exception e) {
            ConsoleLog.warn("列出集合失败，降级搜索单个集合: " + e.getMessage());
            return hybridRetriever.retrieve(query, collection).getResults();
        }

        List<SearchResult> allResults = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String col : cols) {
            String shortName = (collectionPrefix != null && col.startsWith(collectionPrefix))
                    ? col.substring(collectionPrefix.length()) : col;
            try {
                for (SearchResult s : hybridRetriever.retrieve(query, shortName).getResults()) {
                    String key = s.getContent().trim();
                    if (!seen.contains(key)) {
                        seen.add(key);
                        allResults.add(s);
                    }
                }
            } catch (Exception e) {
                ConsoleLog.dim("集合 " + col + " 检索失败，跳过");
            }
        }
        return allResults;
    }

    /**
     * 用累积的上下文生成最终答案
     */
    private GenerationResult generateAnswer(String query, List<SearchResult> allChunks) {
        RetrievalResult result = new RetrievalResult(query, allChunks, allChunks.size());
        return generator.generate(query, result);
    }

    private enum Action { GENERATE, RETRIEVE, REWRITE_AND_RETRIEVE }

    private record AgentDecision(Action action, String newQuery) {}
}

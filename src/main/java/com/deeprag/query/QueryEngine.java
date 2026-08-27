package com.deeprag.query;

import com.deeprag.log.ConsoleLog;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 查询引擎
 * <p>
 * 编排所有查询处理组件，对用户查询进行意图分类、改写、HyDE 生成、
 * Multi-Query 变体生成和子问题分解，最终输出完整的处理结果
 */
public class QueryEngine {

    private final IntentClassifier intentClassifier;
    private final QueryRewriter queryRewriter;
    private final HyDEGenerator hydeGenerator;
    private final MultiQueryGenerator multiQueryGenerator;
    private final QueryDecomposer queryDecomposer;

    private final boolean enableRewrite;
    private final boolean enableHyDE;
    private final boolean enableMultiQuery;
    private final boolean enableDecomposition;

    public QueryEngine(ChatLanguageModel llm,
                       boolean enableRewrite,
                       boolean enableHyDE,
                       boolean enableMultiQuery,
                       boolean enableDecomposition) {
        this.intentClassifier = new IntentClassifier(llm);
        this.queryRewriter = new QueryRewriter(llm);
        this.hydeGenerator = new HyDEGenerator(llm);
        this.multiQueryGenerator = new MultiQueryGenerator(llm);
        this.queryDecomposer = new QueryDecomposer(llm);

        this.enableRewrite = enableRewrite;
        this.enableHyDE = enableHyDE;
        this.enableMultiQuery = enableMultiQuery;
        this.enableDecomposition = enableDecomposition;

        ConsoleLog.info("查询引擎初始化完成 (改写=" + enableRewrite
                + ", HyDE=" + enableHyDE
                + ", Multi-Query=" + enableMultiQuery
                + ", 分解=" + enableDecomposition + ")");
    }

    /**
     * 处理用户查询
     * <p>
     * 处理流程：
     * 1. 始终进行意图分类
     * 2. 若为 CHITCHAT：跳过所有后续步骤，直接返回
     * 3. 若启用改写：对查询进行改写
     * 4. 若为 COMPARISON 且启用分解：进行子问题分解
     * 5. 若为 FACTUAL/PROCEDURAL 且启用 HyDE：生成假设性文档
     * 6. 若启用 Multi-Query：生成查询变体
     *
     * @param query 原始用户查询
     * @return 处理后的查询结果
     */
    public ProcessedQuery process(String query) {
        ConsoleLog.step("开始处理查询: " + query);
        ProcessedQuery result = new ProcessedQuery(query);

        // 第一步：意图分类（始终执行）
        Intent intent = intentClassifier.classify(query);
        result.setIntent(intent);

        // 若为闲聊，直接返回
        if (intent == Intent.CHITCHAT) {
            ConsoleLog.step("闲聊查询，跳过检索增强处理");
            return result;
        }

        // 确定用于后续步骤的工作查询文本
        String workingQuery = query;

        // 第二步：查询改写
        if (enableRewrite) {
            String rewritten = queryRewriter.rewrite(workingQuery);
            result.setRewritten(rewritten);
            workingQuery = rewritten;
        }

        // 第三步：根据意图进行不同处理
        if (intent == Intent.COMPARISON && enableDecomposition) {
            // 对比查询：分解为子问题
            result.setSubQueries(queryDecomposer.decompose(workingQuery));
        } else if ((intent == Intent.FACTUAL || intent == Intent.PROCEDURAL) && enableHyDE) {
            // 事实/步骤查询：生成 HyDE 假设性答案
            result.setHydeAnswer(hydeGenerator.generate(workingQuery));
        }

        // 第四步：Multi-Query 变体生成
        if (enableMultiQuery) {
            result.setVariants(multiQueryGenerator.generate(workingQuery));
        }

        ConsoleLog.step("查询处理完成");
        return result;
    }
}

package com.deeprag.stage4;

import com.deeprag.chunker.FixedSizeChunker;
import com.deeprag.config.DeepRagConfig;
import com.deeprag.embedding.EmbeddingService;
import com.deeprag.evaluator.Evaluator;
import com.deeprag.generator.*;
import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.ParserRouter;
import com.deeprag.pipeline.RAGPipeline;
import com.deeprag.query.QueryEngine;
import com.deeprag.retriever.DenseRetriever;
import com.deeprag.retriever.HybridRetriever;
import com.deeprag.retriever.Retriever;
import com.deeprag.store.MilvusVectorStore;
import com.deeprag.store.VectorStore;
import com.deeprag.strategy.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.*;

public class Stage4App {

    private static final String BANNER = """

            ╔══════════════════════════════════════════════╗
            ║         DeepRAG Engine - Stage 4             ║
            ║       Agentic RAG + 多轮动态检索              ║
            ╚══════════════════════════════════════════════╝
            """;

    private static final String HELP_TEXT = """

            可用命令:
              index <path>                  索引文档
              query <text>                  使用当前策略查询
              query naive|advanced|self_rag|crag|adaptive|agentic <text>
                                            使用指定策略查询
              strategy <name>               切换默认策略
              eval                          运行评测
              compare                       对比所有策略
              status                        显示状态
              collections                   列出所有集合
              help                          帮助
              quit                          退出
            """;

    public static void main(String[] args) {
        System.out.println(BANNER);

        String configPath = "config/stage4.yml";
        DeepRagConfig config = DeepRagConfig.load(configPath);
        ConsoleLog.step("配置加载完成");

        // 核心组件
        EmbeddingService embeddingService = new EmbeddingService(config.getEmbedding());
        VectorStore vectorStore = new MilvusVectorStore(config.getVectorStore());
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(config.getLlm().getBaseUrl())
                .apiKey(config.getLlm().getApiKey())
                .modelName(config.getLlm().getModel())
                .timeout(Duration.ofSeconds(config.getLlm().getTimeout()))
                .build();

        // 检索器
        Retriever denseRetriever = new DenseRetriever(embeddingService, vectorStore, config.getRetriever());
        Retriever hybridRetriever = new HybridRetriever(embeddingService, vectorStore,
                config.getRetriever().getTopK(), config.getRetriever().getScoreThreshold(),
                config.getRetriever().getDenseWeight(), config.getRetriever().getSparseWeight(),
                config.getRetriever().isDynamicWeight(), config.getRetriever().getRrfK());

        // Generator
        Generator citationGenerator = new CitationGenerator(chatModel);
        Generator simpleGenerator = new SimpleGenerator(config.getLlm());

        // Query Engine
        QueryEngine queryEngine = new QueryEngine(chatModel,
                config.getQueryEngine().isEnableRewrite(),
                config.getQueryEngine().isEnableHyDE(),
                config.getQueryEngine().isEnableMultiQuery(),
                config.getQueryEngine().isEnableDecomposition());

        // 所有策略
        Map<String, RAGStrategy> strategies = new LinkedHashMap<>();
        strategies.put("naive", new NaiveRAGStrategy(denseRetriever, simpleGenerator));
        strategies.put("advanced", new AdvancedRAGStrategy(hybridRetriever, citationGenerator,
                queryEngine, new com.deeprag.reranker.NoOpReranker(), null, null, config));
        strategies.put("self_rag", new SelfRAGStrategy(hybridRetriever, citationGenerator,
                chatModel, queryEngine, 2));
        strategies.put("crag", new CRAGStrategy(hybridRetriever, citationGenerator,
                queryEngine, 0.8, 0.5));
        strategies.put("adaptive", new AdaptiveRAGStrategy(chatModel, denseRetriever,
                hybridRetriever, citationGenerator, queryEngine,
                (SelfRAGStrategy) strategies.get("self_rag"), true, 5));

        // Stage 4 核心策略：Agentic RAG
        int maxIterations = config.getStrategy() != null && config.getStrategy().getAgentic() != null
                ? config.getStrategy().getAgentic().getMaxIterations() : 5;
        strategies.put("agentic", new AgenticRAGStrategy(chatModel, denseRetriever,
                hybridRetriever, citationGenerator, queryEngine, vectorStore,
                config.getVectorStore().getCollectionPrefix(), maxIterations));

        String currentStrategyName = "agentic";
        RAGStrategy currentStrategy = strategies.get(currentStrategyName);

        // 启动时加载已有集合
        try {
            List<String> existing = vectorStore.listCollections();
            if (!existing.isEmpty()) {
                String prefix = config.getVectorStore().getCollectionPrefix();
                String fullName = existing.get(0);
                String shortName = fullName.startsWith(prefix) ? fullName.substring(prefix.length()) : fullName;
                ConsoleLog.step("已有 " + existing.size() + " 个集合，默认使用: " + shortName);
                ConsoleLog.dim("用 collections 查看全部");
            }
        } catch (Exception e) {
            ConsoleLog.warn("无法加载已有集合: " + e.getMessage());
        }

        // Pipeline & Evaluator
        RAGPipeline pipeline = new RAGPipeline(new ParserRouter(),
                new FixedSizeChunker(config.getChunker().getMaxSize(), config.getChunker().getOverlap()),
                embeddingService, vectorStore, denseRetriever, simpleGenerator);
        // Evaluator：用独立的 Judge 模型（与生成器分开，避免自己评自己）
        var judgeCfg = config.getJudge() != null ? config.getJudge() : config.getLlm();
        ChatLanguageModel judgeModel = OpenAiChatModel.builder()
                .baseUrl(judgeCfg.getBaseUrl())
                .apiKey(judgeCfg.getApiKey())
                .modelName(judgeCfg.getModel())
                .timeout(Duration.ofSeconds(judgeCfg.getTimeout()))
                .build();
        Evaluator evaluator = new Evaluator(judgeModel, config.getEvaluation().getDatasetPath());

        ConsoleLog.info("系统就绪 (策略: " + currentStrategy.getName() + ")");

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.print("deeprag-s4> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+", 3);
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "index" -> {
                        if (parts.length < 2) { ConsoleLog.warn("用法: index <路径>"); break; }
                        pipeline.indexDocument(parts[1]);
                        String collection = pipeline.toCollectionName(parts[1]);
                        ConsoleLog.info("集合: " + collection);
                    }
                    case "query" -> {
                        if (parts.length < 2) { ConsoleLog.warn("用法: query [策略名] <文本>"); break; }

                        RAGStrategy strategy = currentStrategy;
                        String queryText;
                        if (parts.length >= 3 && strategies.containsKey(parts[1])) {
                            strategy = strategies.get(parts[1]);
                            queryText = parts[2];
                        } else {
                            queryText = parts.length >= 3 ? parts[1] + " " + parts[2] : parts[1];
                        }

                        ConsoleLog.info("策略: " + strategy.getName());

                        // 先用 dense 检索快速定位最佳集合，再跑完整策略
                        GenerationResult bestResult = null;
                        try {
                            List<String> allCols = vectorStore.listCollections();
                            if (allCols.isEmpty()) {
                                ConsoleLog.warn("没有任何集合，请先索引文档");
                                break;
                            }

                            // 快速检索：只在每个集合做一次 dense 检索，找到分数最高的集合
                            String bestCollection = null;
                            double bestQuickScore = -1;
                            String prefix = config.getVectorStore().getCollectionPrefix();
                            for (String col : allCols) {
                                try {
                                    String shortName = col.startsWith(prefix) ? col.substring(prefix.length()) : col;
                                    var quickResult = denseRetriever.retrieve(queryText, shortName);
                                    double avg = quickResult.getResults() != null && !quickResult.getResults().isEmpty()
                                            ? quickResult.getResults().stream().mapToDouble(s -> s.getScore()).average().orElse(0)
                                            : 0;
                                    if (avg > bestQuickScore) {
                                        bestQuickScore = avg;
                                        bestCollection = shortName;
                                    }
                                } catch (Exception e) {
                                    ConsoleLog.dim("集合 " + col + " 快速检索失败，跳过");
                                }
                            }

                            if (bestCollection == null) {
                                ConsoleLog.warn("没有找到匹配的集合");
                                break;
                            }
                            ConsoleLog.dim("最佳集合: " + bestCollection + " (快速检索均分: " + String.format("%.3f", bestQuickScore) + ")");

                            // 在最佳集合上跑完整策略
                            bestResult = strategy.execute(queryText, bestCollection);
                        } catch (Exception e) {
                            ConsoleLog.error("获取集合列表失败: " + e.getMessage());
                            break;
                        }

                        if (bestResult != null) {
                            ConsoleLog.header("回答");
                            System.out.println(bestResult.getAnswer());
                            if (bestResult.getHallucinationScore() >= 0) {
                                ConsoleLog.dim("幻觉分数: " + String.format("%.2f", bestResult.getHallucinationScore()));
                            }
                        }
                    }
                    case "strategy" -> {
                        if (parts.length < 2) {
                            ConsoleLog.info("可用策略: " + String.join(", ", strategies.keySet()));
                            break;
                        }
                        if (strategies.containsKey(parts[1])) {
                            currentStrategyName = parts[1];
                            currentStrategy = strategies.get(currentStrategyName);
                            ConsoleLog.info("切换到: " + currentStrategy.getName());
                        } else {
                            ConsoleLog.warn("未知策略: " + parts[1]);
                        }
                    }
                    case "eval" -> {
                        ConsoleLog.info("策略: " + currentStrategy.getName());
                        RAGStrategy evalStrategy = currentStrategy;
                        evaluator.evaluate(evalStrategy.getName(), (col, q) -> evalStrategy.execute(q, col));
                    }
                    case "compare" -> {
                        ConsoleLog.header("策略对比评测（adaptive vs agentic）");
                        String[] compareKeys = {"adaptive", "agentic"};
                        for (int si = 0; si < compareKeys.length; si++) {
                            String key = compareKeys[si];
                            if (strategies.containsKey(key)) {
                                RAGStrategy s = strategies.get(key);
                                ConsoleLog.step(String.format("【%d/%d】评测策略: %s", si + 1, compareKeys.length, s.getName()));
                                evaluator.evaluate(s.getName(), (col, q) -> s.execute(q, col));
                                ConsoleLog.blank();
                            }
                        }
                        ConsoleLog.header("对比评测完成");
                    }
                    case "status" -> {
                        ConsoleLog.header("系统状态");
                        ConsoleLog.step("当前策略: " + currentStrategy.getName());
                        ConsoleLog.step("可用策略: " + String.join(", ", strategies.keySet()));
                        try { ConsoleLog.step("集合: " + vectorStore.listCollections()); }
                        catch (Exception e) { ConsoleLog.warn("无法获取集合列表"); }
                    }
                    case "collections" -> {
                        try {
                            List<String> collections = vectorStore.listCollections();
                            ConsoleLog.step("集合列表 (" + collections.size() + "):");
                            for (String c : collections) {
                                ConsoleLog.dim("  - " + c);
                            }
                        } catch (Exception e) {
                            ConsoleLog.error("获取集合列表失败: " + e.getMessage());
                        }
                    }
                    case "help" -> System.out.println(HELP_TEXT);
                    case "quit", "exit" -> { 
                        ConsoleLog.info("再见！"); 
                        scanner.close();
                        running = false; 
                    }
                    default -> ConsoleLog.warn("未知命令，输入 help");
                }
            } catch (Exception e) {
                ConsoleLog.error("执行失败: " + e.getMessage());
            }
        }
    }
}

package com.deeprag.stage3;

import com.deeprag.api.RAGApiController;
import com.deeprag.api.RAGIndexer;
import com.deeprag.chunker.*;
import com.deeprag.config.DeepRagConfig;
import com.deeprag.embedding.EmbeddingService;
import com.deeprag.evaluator.Evaluator;
import com.deeprag.generator.*;
import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.ParserRouter;
import com.deeprag.pipeline.RAGPipeline;
import com.deeprag.query.QueryEngine;
import com.deeprag.reranker.*;
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

public class Stage3App {

    private static final String BANNER = """

            ╔══════════════════════════════════════════════╗
            ║         DeepRAG Engine - Stage 3             ║
            ║       企业级智能知识检索引擎 v3.0             ║
            ║         高级 RAG 架构                         ║
            ╚══════════════════════════════════════════════╝
            """;

    private static final String HELP_TEXT = """

            可用命令:
              index <path>                  索引文档
              query <text>                  使用当前策略查询
              query naive|advanced|self_rag|crag|adaptive <text>
                                            使用指定策略查询
              strategy <name>               切换默认策略
              eval                          运行评测
              compare                       对比所有策略
              status                        显示状态
              collections                   列出所有集合
              use <集合名>                  切换当前集合
              help                          帮助
              quit                          退出
            """;

    public static void main(String[] args) {
        System.out.println(BANNER);

        String configPath = "config/stage3.yml";
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
                queryEngine, new NoOpReranker(), null, null, config));
        strategies.put("self_rag", new SelfRAGStrategy(hybridRetriever, citationGenerator,
                chatModel, queryEngine, 2));
        strategies.put("crag", new CRAGStrategy(hybridRetriever, citationGenerator,
                queryEngine, 0.8, 0.5));
        strategies.put("adaptive", new AdaptiveRAGStrategy(chatModel, denseRetriever,
                hybridRetriever, citationGenerator, queryEngine,
                (SelfRAGStrategy) strategies.get("self_rag"), true, 5));

        String currentStrategyName = "adaptive";
        RAGStrategy currentStrategy = strategies.get(currentStrategyName);
        String lastCollection = "";

        // 启动时加载已有集合，避免每次重启都要重新索引
        try {
            List<String> existing = vectorStore.listCollections();
            if (!existing.isEmpty()) {
                String prefix = config.getVectorStore().getCollectionPrefix();
                String fullName = existing.get(0);
                lastCollection = fullName.startsWith(prefix) ? fullName.substring(prefix.length()) : fullName;
                ConsoleLog.step("已有 " + existing.size() + " 个集合，默认使用: " + lastCollection);
                ConsoleLog.dim("用 use <集合名> 切换，用 collections 查看全部");
            }
        } catch (Exception e) {
            ConsoleLog.warn("无法加载已有集合: " + e.getMessage());
        }

        // Pipeline & Evaluator
        Chunker chunker = new FixedSizeChunker(config.getChunker().getMaxSize(), config.getChunker().getOverlap());
        RAGPipeline pipeline = new RAGPipeline(new ParserRouter(), chunker, embeddingService,
                vectorStore, denseRetriever, simpleGenerator);
        Evaluator evaluator = new Evaluator(chatModel, config.getEvaluation().getDatasetPath());

        ConsoleLog.info("系统就绪 (策略: " + currentStrategy.getName() + ")");

        // 启动 HTTP API（如果配置了 api.port）
        if (config.getApi() != null && config.getApi().getPort() > 0) {
            try {
                RAGIndexer indexer = new RAGIndexer() {
                    @Override
                    public String index(String filePath) {
                        pipeline.indexDocument(filePath);
                        return pipeline.toCollectionName(filePath);
                    }
                    @Override
                    public List<String> listCollections() { return vectorStore.listCollections(); }
                };
                var api = new RAGApiController(config.getApi().getPort(), currentStrategy, indexer, null, null);
                api.start();
            } catch (java.io.IOException e) {
                ConsoleLog.error("HTTP API 启动失败: " + e.getMessage());
            }
        }

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.print("deeprag-s3> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+", 3);
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "index" -> {
                        if (parts.length < 2) { ConsoleLog.warn("用法: index <路径>"); break; }
                        pipeline.indexDocument(parts[1]);
                        lastCollection = pipeline.toCollectionName(parts[1]);
                        ConsoleLog.info("集合: " + lastCollection);
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

                        // 始终搜索所有集合，取检索分数最高的结果
                        GenerationResult bestResult = null;
                        try {
                            List<String> allCols = vectorStore.listCollections();
                            if (allCols.isEmpty()) {
                                ConsoleLog.warn("没有任何集合，请先索引文档");
                                break;
                            }
                            String prefix = config.getVectorStore().getCollectionPrefix();
                            double bestScore = -1;
                            for (String col : allCols) {
                                try {
                                    String shortName = col.startsWith(prefix) ? col.substring(prefix.length()) : col;
                                    ConsoleLog.dim("搜索集合: " + shortName);
                                    var r = strategy.execute(queryText, shortName);
                                    double avg = r.getRetrievedChunks() != null && !r.getRetrievedChunks().isEmpty()
                                            ? r.getRetrievedChunks().stream().mapToDouble(s -> s.getScore()).average().orElse(0)
                                            : 0;
                                    if (avg > bestScore) {
                                        bestScore = avg;
                                        bestResult = r;
                                    }
                                } catch (Exception e) {
                                    ConsoleLog.dim("集合 " + col + " 搜索失败，跳过");
                                }
                            }
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
                        ConsoleLog.header("策略对比评测");
                        for (var entry : strategies.entrySet()) {
                            ConsoleLog.step("=== " + entry.getValue().getName() + " ===");
                            evaluator.evaluate(entry.getValue().getName(), (col, q) -> entry.getValue().execute(q, col));
                            ConsoleLog.blank();
                        }
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
                    case "use" -> {
                        if (parts.length < 2) {
                            ConsoleLog.warn("用法: use <集合名>");
                            ConsoleLog.info("当前集合: " + (lastCollection.isEmpty() ? "(无)" : lastCollection));
                        } else {
                            String target = parts[1];
                            String prefix = config.getVectorStore().getCollectionPrefix();
                            String fullName = target.startsWith(prefix) ? target : prefix + target;
                            try {
                                List<String> cols = vectorStore.listCollections();
                                if (cols.contains(fullName)) {
                                    lastCollection = target;
                                    ConsoleLog.step("切换到集合: " + target);
                                } else {
                                    ConsoleLog.warn("集合不存在: " + fullName);
                                    ConsoleLog.dim("可用集合: " + cols);
                                }
                            } catch (Exception e) {
                                ConsoleLog.error("查询集合失败: " + e.getMessage());
                            }
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

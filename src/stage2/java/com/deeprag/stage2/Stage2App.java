package com.deeprag.stage2;

import com.deeprag.api.RAGApiController;
import com.deeprag.api.RAGIndexer;
import com.deeprag.chunker.*;
import com.deeprag.config.DeepRagConfig;
import com.deeprag.embedding.EmbeddingService;
import com.deeprag.evaluator.EvaluationReport;
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
import com.deeprag.strategy.AdvancedRAGStrategy;
import com.deeprag.strategy.NaiveRAGStrategy;
import com.deeprag.strategy.RAGStrategy;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;

public class Stage2App {

    private static final String BANNER = """

            ╔══════════════════════════════════════════════╗
            ║         DeepRAG Engine - Stage 2             ║
            ║       企业级智能知识检索引擎 v2.0             ║
            ║         全链路深度优化版                      ║
            ╚══════════════════════════════════════════════╝
            """;

    private static final String HELP_TEXT = """

            可用命令:
              index <path>         索引文档到向量数据库
              query <text>         执行 RAG 查询
              query naive <text>   使用 Stage 1 朴素策略查询
              query adv <text>     使用 Stage 2 高级策略查询
              eval                 运行评估集评测
              compare              对比 Naive vs Advanced 评测
              chunk <strategy>     切换分块策略
              status               显示当前状态
              collections          列出所有集合
              use <集合名>         切换当前集合
              help                 显示帮助信息
              quit                 退出程序
            """;

    public static void main(String[] args) {
        System.out.println(BANNER);

        String configPath = "config/stage2.yml";
        ConsoleLog.info("加载配置: " + configPath);
        DeepRagConfig config = DeepRagConfig.load(configPath);
        ConsoleLog.step("配置加载完成");

        // 核心组件
        EmbeddingService embeddingService = new EmbeddingService(config.getEmbedding());
        VectorStore vectorStore = new MilvusVectorStore(config.getVectorStore());
        ParserRouter parserRouter = new ParserRouter();

        // 根据配置选择分块策略
        Chunker chunker = createChunker(config, embeddingService);

        // LLM 客户端
        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .baseUrl(config.getLlm().getBaseUrl())
                .apiKey(config.getLlm().getApiKey())
                .modelName(config.getLlm().getModel())
                .timeout(Duration.ofSeconds(config.getLlm().getTimeout()))
                .build();

        // 根据配置选择检索器
        Retriever retriever = createRetriever(config, embeddingService, vectorStore);

        // Reranker
        Reranker reranker;
        if (config.getReranker() != null && config.getReranker().isEnabled()) {
            if ("llm".equalsIgnoreCase(config.getReranker().getStrategy())) {
                reranker = new LLMReranker(config.getReranker().getBaseUrl(),
                        config.getReranker().getModel(), config.getReranker().getTimeout());
            } else {
                reranker = new BGEReranker(config.getReranker().getBaseUrl(),
                        config.getReranker().getModel(), config.getReranker().getTimeout());
            }
        } else {
            reranker = new NoOpReranker();
        }

        // Generator
        Generator generator;
        if (config.getGenerator() != null && config.getGenerator().isEnableCitation()) {
            generator = new CitationGenerator(chatModel);
        } else {
            generator = new SimpleGenerator(config.getLlm());
        }

        // Context Compressor
        ContextCompressor compressor = null;
        if (config.getGenerator() != null) {
            compressor = new ContextCompressor(
                    config.getGenerator().getContextCompressionThreshold(),
                    config.getGenerator().getMaxContextTokens());
        }

        // Hallucination Detector
        HallucinationDetector hallucinationDetector = null;
        if (config.getGenerator() != null && config.getGenerator().isEnableHallucinationDetection()) {
            hallucinationDetector = new HallucinationDetector(chatModel);
        }

        // Query Engine
        QueryEngine queryEngine = null;
        if (config.getQueryEngine() != null && config.getQueryEngine().isEnabled()) {
            queryEngine = new QueryEngine(chatModel,
                    config.getQueryEngine().isEnableRewrite(),
                    config.getQueryEngine().isEnableHyDE(),
                    config.getQueryEngine().isEnableMultiQuery(),
                    config.getQueryEngine().isEnableDecomposition());
        }

        // 组装策略
        RAGStrategy naiveStrategy = new NaiveRAGStrategy(
                new DenseRetriever(embeddingService, vectorStore, config.getRetriever()),
                new SimpleGenerator(config.getLlm()));

        RAGStrategy advancedStrategy = new AdvancedRAGStrategy(
                retriever, generator, queryEngine, reranker,
                compressor, hallucinationDetector, config);

        // Pipeline
        RAGPipeline pipeline = new RAGPipeline(parserRouter, chunker, embeddingService, vectorStore,
                new DenseRetriever(embeddingService, vectorStore, config.getRetriever()),
                generator);

        // Evaluator：用独立的 Judge 模型（与生成器分开，避免自己评自己）
        var judgeCfg = config.getJudge() != null ? config.getJudge() : config.getLlm();
        ChatLanguageModel judgeModel = OpenAiChatModel.builder()
                .baseUrl(judgeCfg.getBaseUrl())
                .apiKey(judgeCfg.getApiKey())
                .modelName(judgeCfg.getModel())
                .timeout(Duration.ofSeconds(judgeCfg.getTimeout()))
                .build();
        Evaluator evaluator = new Evaluator(judgeModel, config.getEvaluation().getDatasetPath());

        // 当前策略
        RAGStrategy currentStrategy = advancedStrategy;

        ConsoleLog.info("系统就绪 (策略: " + currentStrategy.getName() + ")");
        ConsoleLog.blank();

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
                var api = new RAGApiController(config.getApi().getPort(), advancedStrategy, indexer, null, null);
                api.start();
            } catch (java.io.IOException e) {
                ConsoleLog.error("HTTP API 启动失败: " + e.getMessage());
            }
        }

        // 启动时加载已有集合，避免每次重启都要重新索引
        String lastCollection = "";
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

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.print("deeprag-s2> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+", 3);
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "index" -> {
                        if (parts.length < 2) {
                            ConsoleLog.warn("用法: index <文件路径>");
                            break;
                        }
                        String filePath = parts[1];
                        if (!Path.of(filePath).toFile().exists()) {
                            ConsoleLog.error("文件不存在: " + filePath);
                            break;
                        }
                        pipeline.indexDocument(filePath);
                        lastCollection = pipeline.toCollectionName(filePath);
                        ConsoleLog.info("当前集合: " + lastCollection);
                    }

                    case "query" -> {
                        if (parts.length < 2) {
                            ConsoleLog.warn("用法: query [naive|adv] <查询文本>");
                            break;
                        }

                        RAGStrategy strategy = currentStrategy;
                        String queryText;
                        if (parts.length >= 3 && (parts[1].equals("naive") || parts[1].equals("adv"))) {
                            strategy = parts[1].equals("naive") ? naiveStrategy : advancedStrategy;
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
                            ConsoleLog.blank();
                            ConsoleLog.header("回答");
                            System.out.println(bestResult.getAnswer());
                            if (bestResult.getHallucinationScore() >= 0) {
                                ConsoleLog.dim("幻觉分数: " + String.format("%.2f", bestResult.getHallucinationScore()));
                            }
                            ConsoleLog.blank();
                        }
                    }

                    case "eval" -> {
                        ConsoleLog.info("使用策略: " + currentStrategy.getName());
                        EvaluationReport report = evaluator.evaluate(
                                currentStrategy.getName(),
                                (collection, query) -> currentStrategy.execute(query, collection));
                        ConsoleLog.info("评测完成");
                    }

                    case "compare" -> {
                        ConsoleLog.header("对比评测: Naive vs Advanced");
                        ConsoleLog.step("=== Naive RAG ===");
                        evaluator.evaluate(naiveStrategy.getName(), (col, q) -> naiveStrategy.execute(q, col));
                        ConsoleLog.blank();
                        ConsoleLog.step("=== Advanced RAG ===");
                        evaluator.evaluate(advancedStrategy.getName(), (col, q) -> advancedStrategy.execute(q, col));
                    }

                    case "chunk" -> {
                        if (parts.length < 2) {
                            ConsoleLog.warn("可用策略: fixed_size, recursive, semantic, parent_child, structure_aware");
                            break;
                        }
                        ConsoleLog.info("切换分块策略需要在配置文件中修改 chunker.strategy 并重启");
                    }

                    case "status" -> {
                        ConsoleLog.header("系统状态");
                        ConsoleLog.step("当前策略: " + currentStrategy.getName());
                        ConsoleLog.step("分块策略: " + config.getChunker().getStrategy());
                        ConsoleLog.step("检索策略: " + config.getRetriever().getStrategy());
                        ConsoleLog.step("Reranker: " + (config.getReranker() != null && config.getReranker().isEnabled()
                                ? config.getReranker().getModel() : "关闭"));
                        ConsoleLog.step("Query 引擎: " + (queryEngine != null ? "开启" : "关闭"));
                        ConsoleLog.step("引用标注: " + (config.getGenerator() != null && config.getGenerator().isEnableCitation() ? "开启" : "关闭"));
                        ConsoleLog.step("幻觉检测: " + (hallucinationDetector != null ? "开启" : "关闭"));
                        try {
                            List<String> collections = vectorStore.listCollections();
                            ConsoleLog.step("已有集合: " + collections);
                        } catch (Exception e) {
                            ConsoleLog.warn("无法获取集合列表");
                        }
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
                            // 如果传入的名称不含前缀，自动加上
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
                    default -> ConsoleLog.warn("未知命令: " + command + "，输入 help 查看帮助");
                }
            } catch (Exception e) {
                ConsoleLog.error("执行失败: " + e.getMessage());
            }
        }
    }

    private static Chunker createChunker(DeepRagConfig config, EmbeddingService embeddingService) {
        String strategy = config.getChunker().getStrategy();
        return switch (strategy) {
            case "recursive" -> new RecursiveChunker(config.getChunker().getMaxSize(), config.getChunker().getOverlap());
            case "semantic" -> new SemanticChunker(embeddingService, config.getChunker().getSemanticThreshold(),
                    config.getChunker().getMaxSize(), config.getChunker().getOverlap());
            case "parent_child" -> new ParentChildChunker(config.getChunker().getMaxSize(), config.getChunker().getOverlap(),
                    config.getChunker().getChildSize(), config.getChunker().getChildOverlap());
            case "structure_aware" -> new StructureAwareChunker(config.getChunker().getMaxSize(), config.getChunker().getOverlap());
            default -> new FixedSizeChunker(config.getChunker().getMaxSize(), config.getChunker().getOverlap());
        };
    }

    private static Retriever createRetriever(DeepRagConfig config,
                                              EmbeddingService embeddingService,
                                              VectorStore vectorStore) {
        if (config.getRetriever().getStrategy().equals("hybrid")) {
            return new HybridRetriever(embeddingService, vectorStore,
                    config.getRetriever().getTopK(),
                    config.getRetriever().getScoreThreshold(),
                    config.getRetriever().getDenseWeight(),
                    config.getRetriever().getSparseWeight(),
                    config.getRetriever().isDynamicWeight(),
                    config.getRetriever().getRrfK());
        }
        return new DenseRetriever(embeddingService, vectorStore, config.getRetriever());
    }
}

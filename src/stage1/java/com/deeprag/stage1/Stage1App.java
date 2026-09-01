package com.deeprag.stage1;

import com.deeprag.api.RAGApiController;
import com.deeprag.api.RAGIndexer;
import com.deeprag.chunker.Chunker;
import com.deeprag.chunker.FixedSizeChunker;
import com.deeprag.config.DeepRagConfig;
import com.deeprag.embedding.EmbeddingService;
import com.deeprag.evaluator.EvaluationReport;
import com.deeprag.evaluator.Evaluator;
import com.deeprag.generator.GenerationResult;
import com.deeprag.generator.SimpleGenerator;
import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.ParserRouter;
import com.deeprag.pipeline.RAGPipeline;
import com.deeprag.retriever.DenseRetriever;
import com.deeprag.strategy.NaiveRAGStrategy;
import com.deeprag.store.MilvusVectorStore;
import com.deeprag.store.VectorStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;

/**
 * DeepRAG Engine Stage 1 入口
 * <p>
 * 提供交互式 CLI 界面，支持以下命令：
 * - index <path>  : 索引指定文档
 * - query <text>  : 对最近索引的集合执行查询
 * - evaluate      : 运行评估集进行评测
 * - status        : 显示当前状态
 * - help          : 显示帮助信息
 * - quit          : 退出程序
 */
public class Stage1App {

    private static final String BANNER = """

            ╔══════════════════════════════════════════════╗
            ║         DeepRAG Engine - Stage 1             ║
            ║       企业级智能知识检索引擎 v1.0             ║
            ╚══════════════════════════════════════════════╝
            """;

    private static final String HELP_TEXT = """

            可用命令:
              index <path>    索引文档到向量数据库
              query <text>    执行 RAG 查询
              evaluate        运行评估集评测
              status          显示当前状态
              collections     列出所有集合
              use <集合名>    切换当前集合
              help            显示帮助信息
              quit            退出程序
            """;

    public static void main(String[] args) {
        System.out.println(BANNER);

        // 加载配置
        String configPath = "config/stage1.yml";
        ConsoleLog.info("加载配置: " + configPath);
        DeepRagConfig config = DeepRagConfig.load(configPath);
        ConsoleLog.step("配置加载完成");

        // 创建核心组件（构造函数注入，不依赖Spring）
        EmbeddingService embeddingService = new EmbeddingService(config.getEmbedding());
        VectorStore vectorStore = new MilvusVectorStore(config.getVectorStore());
        ParserRouter parserRouter = new ParserRouter();
        Chunker chunker = new FixedSizeChunker(
                config.getChunker().getMaxSize(),
                config.getChunker().getOverlap()
        );
        // 创建检索器和生成器
        DenseRetriever retriever = new DenseRetriever(
                embeddingService,
                vectorStore,
                config.getRetriever()
        );
        SimpleGenerator generator = new SimpleGenerator(config.getLlm());

        // 组装 RAG 管线(解析 -> 分块 -> Embedding -> 检索 -> 生成)
        RAGPipeline pipeline = new RAGPipeline(
                parserRouter, chunker, embeddingService,
                vectorStore, retriever, generator
        );

        // 创建用于评测的 Judge 模型（独立配置，与生成器分开）
        var judgeCfg = config.getJudge() != null ? config.getJudge() : config.getLlm();
        ChatLanguageModel judgeModel = OpenAiChatModel.builder()
                .baseUrl(judgeCfg.getBaseUrl())
                .apiKey(judgeCfg.getApiKey())
                .modelName(judgeCfg.getModel())
                .timeout(Duration.ofSeconds(judgeCfg.getTimeout()))
                .build();
        Evaluator evaluator = new Evaluator(judgeModel, config.getEvaluation().getDatasetPath());

        // 启动 HTTP API（如果配置了 api.port）
        if (config.getApi() != null && config.getApi().getPort() > 0) {
            try {
                var naiveStrategy = new NaiveRAGStrategy(retriever, generator);
                RAGIndexer indexer = new RAGIndexer() {
                    @Override
                    public String index(String filePath) {
                        pipeline.indexDocument(filePath);
                        return pipeline.toCollectionName(filePath);
                    }
                    @Override
                    public List<String> listCollections() { return vectorStore.listCollections(); }
                };
                var api = new RAGApiController(config.getApi().getPort(), naiveStrategy, indexer, null, null);
                api.start();
            } catch (java.io.IOException e) {
                ConsoleLog.error("HTTP API 启动失败: " + e.getMessage());
            }
        }

        // CLI 交互循环
        Scanner scanner = new Scanner(System.in);
        String lastCollection = "";
        boolean running = true;

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

        ConsoleLog.info("系统就绪，输入 help 查看可用命令");
        ConsoleLog.blank();

        while (running) {
            System.out.print("deeprag> ");
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
                        // 检查文件是否存在
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
                            ConsoleLog.warn("用法: query <查询文本>");
                            break;
                        }
                        String queryText = parts.length >= 3 ? parts[1] + " " + parts[2] : parts[1];

                        // 始终搜索所有集合，取检索分数最高的结果
                        GenerationResult result = null;
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
                                    var r = pipeline.query(shortName, queryText);
                                    double avg = r.getRetrievedChunks() != null && !r.getRetrievedChunks().isEmpty()
                                            ? r.getRetrievedChunks().stream().mapToDouble(s -> s.getScore()).average().orElse(0)
                                            : 0;
                                    if (avg > bestScore) {
                                        bestScore = avg;
                                        result = r;
                                    }
                                } catch (Exception e) {
                                    ConsoleLog.dim("集合 " + col + " 搜索失败，跳过");
                                }
                            }
                        } catch (Exception e) {
                            ConsoleLog.error("获取集合列表失败: " + e.getMessage());
                            break;
                        }

                        if (result != null) {
                            ConsoleLog.blank();
                            ConsoleLog.header("RAG 回答");
                            System.out.println(result.getAnswer());
                            ConsoleLog.blank();
                            ConsoleLog.dim("检索到 " + result.getRetrievedChunks().size() + " 条相关片段");
                        }
                    }

                    case "evaluate" -> {
                        ConsoleLog.info("开始运行评估集...");
                        @SuppressWarnings("unused")
                        EvaluationReport report = evaluator.evaluate(
                                "Stage1-DenseRetrieval",
                                (collection, query) -> pipeline.query(collection, query)
                        );
                        ConsoleLog.info("评测完成");
                    }

                    case "status" -> {
                        ConsoleLog.header("系统状态");
                        ConsoleLog.step("当前集合: " + (lastCollection.isEmpty() ? "(无)" : lastCollection));
                        ConsoleLog.step("Embedding 模型: " + config.getEmbedding().getModel());
                        ConsoleLog.step("LLM 模型: " + config.getLlm().getModel());
                        ConsoleLog.step("分块策略: " + config.getChunker().getStrategy()
                                + " (maxSize=" + config.getChunker().getMaxSize()
                                + ", overlap=" + config.getChunker().getOverlap() + ")");
                        ConsoleLog.step("检索参数: topK=" + config.getRetriever().getTopK()
                                + ", threshold=" + config.getRetriever().getScoreThreshold());
                        try {
                            List<String> collections = vectorStore.listCollections();
                            ConsoleLog.step("已有集合: " + collections);
                        } catch (Exception e) {
                            ConsoleLog.warn("无法获取集合列表: " + e.getMessage());
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
                        running = false;
                    }

                    default -> ConsoleLog.warn("未知命令: " + command + "，输入 help 查看帮助");
                }
            } catch (Exception e) {
                ConsoleLog.error("执行失败: " + e.getMessage());
                e.printStackTrace();
            }

            ConsoleLog.blank();
        }

        scanner.close();
    }
}

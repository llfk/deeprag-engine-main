package com.deeprag.config;

import lombok.Data;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * DeepRAG 引擎全局配置
 * <p>
 * 从 YAML 文件加载所有配置项，按 Stage 渐进式扩展：
 * <ul>
 *   <li>Stage 1 核心配置：LLM、Embedding、VectorStore、Chunker、Retriever、Evaluation</li>
 *   <li>Stage 2 新增配置：QueryEngine、Reranker、Generator（Stage 1 中为 null）</li>
 *   <li>Stage 3 新增配置：Strategy（Self-RAG / CRAG / Adaptive）</li>
 *   <li>Stage 4 新增配置：API 服务、语义缓存</li>
 * </ul>
 * 所有嵌套配置类使用 Lombok @Data 自动生成 getter/setter，字段提供默认值。
 */
@Data
public class DeepRagConfig {
    // ===== Stage 1 核心配置 =====
    private LlmConfig llm;
    private LlmConfig judge;
    private EmbeddingConfig embedding;
    private VectorStoreConfig vectorStore;
    private ChunkerConfig chunker;
    private RetrieverConfig retriever;
    private EvaluationConfig evaluation;
    // ===== Stage 2 新增配置（Stage 1 中为 null，不影响运行） =====
    private QueryEngineConfig queryEngine;
    private RerankerConfig reranker;
    private GeneratorConfig generator;
    // ===== Stage 3 新增 =====
    private StrategyConfig strategy;
    // ===== Stage 4 新增 =====
    private ApiConfig api;
    private CacheConfig cache;

    /** LLM 大语言模型配置（默认使用 Ollama 本地模型） */
    @Data
    public static class LlmConfig {
        private String baseUrl = "http://localhost:11434/v1";
        private String apiKey = "ollama";
        private String model = "qwen2.5";
        private int timeout = 60;
    }

    /** 文本向量化模型配置 */
    @Data
    public static class EmbeddingConfig {
        private String baseUrl = "http://localhost:11434";
        private String model = "nomic-embed-text";
        private int dimension = 768;
        private int timeout = 30;
    }

    /** 向量数据库（Milvus）连接配置 */
    @Data
    public static class VectorStoreConfig {
        private String host = "localhost";
        private int port = 19530;
        private String collectionPrefix = "deeprag_";
    }

    /** 文本分块策略配置 */
    @Data
    public static class ChunkerConfig {
        private String strategy = "fixed_size";
        private int maxSize = 500;
        private int overlap = 50;
        private int childSize = 128;
        private int childOverlap = 30;
        private double semanticThreshold = 0.5;
    }

    /** 检索器配置（支持 dense / hybrid 混合检索） */
    @Data
    public static class RetrieverConfig {
        private String strategy = "dense";
        private int topK = 5;
        private float scoreThreshold = 0.5f;
        private int finalTopK = 5;
        private double denseWeight = 0.5;
        private double sparseWeight = 0.5;
        private boolean dynamicWeight = false;
        private int rrfK = 60;
    }

    /** 查询引擎配置（Stage 2）：控制各查询处理组件的启用/禁用 */
    @Data
    public static class QueryEngineConfig {
        private boolean enabled = false;
        private boolean enableRewrite = true;
        private boolean enableHyDE = true;
        private boolean enableMultiQuery = true;
        private boolean enableDecomposition = true;
    }

    /** 重排序器配置（Stage 2）：使用 BGE-Reranker 模型对检索结果精排 */
    @Data
    public static class RerankerConfig {
        private boolean enabled = false;
        private String strategy = "bge";
        private String baseUrl = "http://localhost:11434";
        private String model = "bge-reranker-v2-m3";
        private int timeout = 30;
    }

    /** 生成器配置（Stage 2）：控制引用标注、幻觉检测和上下文压缩 */
    @Data
    public static class GeneratorConfig {
        private boolean enableCitation = false;
        private boolean enableHallucinationDetection = false;
        private double contextCompressionThreshold = 0.3;
        private int maxContextTokens = 3000;
    }

    /** 评测配置：评估数据集路径和报告输出目录 */
    @Data
    public static class EvaluationConfig {
        private String datasetPath = "evaluation/eval_set.json";
        private String reportPath = "evaluation/reports";
    }

    /** Stage 3 策略配置：选择使用哪种 RAG 策略 */
    @Data
    public static class StrategyConfig {
        private String name = "adaptive";
        private SelfRagConfig selfRag = new SelfRagConfig();
        private CragConfig crag = new CragConfig();
        private AdaptiveConfig adaptive = new AdaptiveConfig();
        private AgenticConfig agentic = new AgenticConfig();
    }

    @Data
    public static class SelfRagConfig {
        private int maxRetries = 2;
    }

    @Data
    public static class CragConfig {
        private double highThreshold = 0.8;
        private double lowThreshold = 0.5;
    }

    @Data
    public static class AdaptiveConfig {
        private boolean parallelSubQueries = true;
        private int maxSubQueries = 5;
    }

    @Data
    public static class AgenticConfig {
        private int maxIterations = 5;
    }

    @Data
    public static class ApiConfig {
        private int port = 8080;
    }

    @Data
    public static class CacheConfig {
        private boolean enabled = false;
        private double similarityThreshold = 0.95;
        private int maxSize = 1000;
    }

    /**
     * 从 YAML 文件加载配置，自动填充缺失的默认值
     *
     * @param path YAML 配置文件路径
     * @return 完整的配置对象
     */
    public static DeepRagConfig load(String path) {
        Yaml yaml = new Yaml(new Constructor(DeepRagConfig.class, new LoaderOptions()));
        try (InputStream is = new FileInputStream(path)) {
            DeepRagConfig config = yaml.load(is);
            // 填充默认值
            if (config.getLlm() == null) config.setLlm(new LlmConfig());
            if (config.getJudge() == null) config.setJudge(config.getLlm());
            if (config.getEmbedding() == null) config.setEmbedding(new EmbeddingConfig());
            if (config.getVectorStore() == null) config.setVectorStore(new VectorStoreConfig());
            if (config.getChunker() == null) config.setChunker(new ChunkerConfig());
            if (config.getRetriever() == null) config.setRetriever(new RetrieverConfig());
            if (config.getEvaluation() == null) config.setEvaluation(new EvaluationConfig());
            return config;
        } catch (Exception e) {
            throw new RuntimeException("加载配置失败: " + path, e);
        }
    }
}

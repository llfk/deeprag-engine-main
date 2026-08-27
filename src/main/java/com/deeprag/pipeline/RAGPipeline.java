package com.deeprag.pipeline;

import com.deeprag.chunker.Chunk;
import com.deeprag.chunker.Chunker;
import com.deeprag.embedding.EmbeddingService;
import com.deeprag.generator.GenerationResult;
import com.deeprag.generator.Generator;
import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.ParseResult;
import com.deeprag.parser.ParserRouter;
import com.deeprag.retriever.Retriever;
import com.deeprag.retriever.RetrievalResult;
import com.deeprag.store.ChunkEmbedding;
import com.deeprag.store.VectorStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 管线编排器
 * <p>
 * 提供两个核心功能：
 * 1. indexDocument：解析文档 -> 分块 -> 向量化 -> 存入 Milvus
 * 2. query：检索 -> 生成回答
 */
public class RAGPipeline {

    private final ParserRouter parserRouter;
    private final Chunker chunker;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final Retriever retriever;
    private final Generator generator;

    public RAGPipeline(ParserRouter parserRouter,
                       Chunker chunker,
                       EmbeddingService embeddingService,
                       VectorStore vectorStore,
                       Retriever retriever,
                       Generator generator) {
        this.parserRouter = parserRouter;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.retriever = retriever;
        this.generator = generator;
        ConsoleLog.info("RAG 管线组装完成");
    }

    /**
     * 索引文档到向量数据库
     * <p>
     * 完整流程：解析 -> 分块 -> 批量向量化 -> 写入 Milvus
     *
     * @param filePath 文档路径
     */
    public void indexDocument(String filePath) {
        ConsoleLog.header("开始索引文档");

        // 第一步：解析文档
        ConsoleLog.step("解析文档: " + filePath);
        ParseResult parseResult = parserRouter.parse(filePath);
        ConsoleLog.step("解析完成 (长度=" + parseResult.getContent().length()
                + ", 标题=" + parseResult.getMetadata().getTitle() + ")");

        // 第二步：文本分块
        ConsoleLog.step("开始文本分块...");
        List<Chunk> chunks = chunker.chunk(parseResult);
        ConsoleLog.step("分块完成 (数量=" + chunks.size() + ")");

        // 第三步：批量向量化
        ConsoleLog.step("开始批量向量化...");
        List<String> texts = chunks.stream().map(Chunk::getContent).toList();
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        ConsoleLog.step("向量化完成 (维度=" + embeddings.get(0).length + ")");

        // 第四步：组装 ChunkEmbedding 并写入 Milvus
        List<ChunkEmbedding> chunkEmbeddings = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            chunkEmbeddings.add(new ChunkEmbedding(
                    chunk.getId(),
                    chunk.getContent(),
                    embeddings.get(i),
                    chunk.getMetadata()
            ));
        }

        // 生成集合名称并创建集合
        String collectionName = toCollectionName(filePath);
        vectorStore.createCollection(collectionName, embeddings.get(0).length);

        // 写入向量数据
        ConsoleLog.step("写入向量数据库...");
        vectorStore.upsert(collectionName, chunkEmbeddings);
        ConsoleLog.info("文档索引完成: " + filePath + " -> " + collectionName);
    }

    /**
     * 执行 RAG 查询
     * <p>
     * 流程：检索相关文档 -> LLM 生成回答
     *
     * @param collection 集合名称
     * @param queryText  查询文本
     * @return 生成结果
     */
    public GenerationResult query(String collection, String queryText) {
        ConsoleLog.step("开始查询: " + queryText);

        // 检索
        RetrievalResult retrievalResult = retriever.retrieve(queryText, collection);

        // 生成回答
        GenerationResult result = generator.generate(queryText, retrievalResult);

        ConsoleLog.step("查询完成");
        return result;
    }

    /**
     * 将文件名转换为合法的 Milvus 集合名称
     * <p>
     * 规则：替换非字母数字字符为下划线，添加前缀确保不以数字开头
     *
     * @param source 源文件路径
     * @return 合法的集合名称
     */
    public String toCollectionName(String source) {
        String fileName = Path.of(source).getFileName().toString();
        // 去掉文件扩展名
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            fileName = fileName.substring(0, dotIdx);
        }
        // 替换非字母数字字符为下划线
        String name = fileName.replaceAll("[^a-zA-Z0-9]", "_");
        // 确保不以数字开头（Milvus 要求）
        if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
            name = "col_" + name;
        }
        // 转小写
        return name.toLowerCase();
    }
}

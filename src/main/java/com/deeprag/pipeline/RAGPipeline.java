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

import java.io.IOException;
import java.nio.file.Files;
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

        // 兜底：内容为空或全为空白时不会产生任何分块，若继续向下走只会在取第 0 个向量时报出无关的越界错误
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文档未产生任何分块（解析内容为空或全为空白），无法索引: " + filePath);
        }

        // 第三步：批量向量化
        ConsoleLog.step("开始批量向量化...");
        List<String> texts = chunks.stream().map(Chunk::getContent).toList();
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        ConsoleLog.step("向量化完成 (维度=" + embeddings.get(0).length + ")");

        // 第四步：组装 ChunkEmbedding 
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
     * 索引入口（文件或目录）
     * <p>
     * 目标是目录时，展开为该目录下所有受支持的文档后逐个索引；
     * 每个文档独立处理，单个文档失败只计入结果，不会中断其余文档。
     *
     * @param path 文档路径或目录路径
     * @return 索引结果汇总
     */
    public IndexReport indexPath(String path) {
        Path target = Path.of(path);
        if (!target.toFile().exists()) {
            ConsoleLog.error("路径不存在: " + path);
            return new IndexReport(List.of(), List.of(path + " -> 路径不存在"));
        }

        List<String> documents = resolveDocuments(path);
        if (documents.isEmpty()) {
            ConsoleLog.warn("目录下没有受支持的文档（支持 .md / .markdown / .pdf）: " + path);
            return new IndexReport(List.of(), List.of());
        }
        return indexDocuments(documents);
    }

    /**
     * 收集待索引的文档路径
     *
     * @param path 文件或目录路径
     * @return 文件自身，或目录下所有受支持文档的路径（按名称排序，不递归子目录）
     */
    public List<String> resolveDocuments(String path) {
        Path target = Path.of(path);
        if (!Files.isDirectory(target)) {
            return List.of(path);
        }
        try (var entries = Files.list(target)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> parserRouter.supports(p.toString()))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取目录失败: " + path, e);
        }
    }

    /**
     * 批量索引文档，并对每个文档做失败隔离
     * <p>
     * 单个文档在解析、分块、向量化或写入的任一环节失败时，只记录失败原因并继续处理
     * 后续文档，避免一个坏文件导致整批入库中断。
     *
     * @param filePaths 文档路径列表
     * @return 索引结果汇总
     */
    public IndexReport indexDocuments(List<String> filePaths) {
        List<String> succeeded = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        ConsoleLog.header("开始批量索引 " + filePaths.size() + " 个文档");
        for (int i = 0; i < filePaths.size(); i++) {
            String filePath = filePaths.get(i);
            ConsoleLog.step("[" + (i + 1) + "/" + filePaths.size() + "] " + filePath);
            try {
                indexDocument(filePath);
                succeeded.add(filePath);
            } catch (Exception e) {
                // 文件级隔离：记录失败原因后继续处理下一个文档
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                failures.add(filePath + " -> " + reason);
                ConsoleLog.error("索引失败，已跳过: " + filePath + " (" + reason + ")");
            }
        }

        ConsoleLog.step("批量索引完成: 成功 " + succeeded.size() + " 个，失败 " + failures.size() + " 个");
        for (String failure : failures) {
            ConsoleLog.dim("  未入库: " + failure);
        }
        return new IndexReport(succeeded, failures);
    }

    /**
     * 批量索引结果汇总
     *
     * @param succeeded 成功索引的文档路径
     * @param failures  失败的文档及原因（格式：路径 -&gt; 原因）
     */
    public record IndexReport(List<String> succeeded, List<String> failures) {
        public int successCount() {
            return succeeded.size();
        }

        public int failureCount() {
            return failures.size();
        }
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

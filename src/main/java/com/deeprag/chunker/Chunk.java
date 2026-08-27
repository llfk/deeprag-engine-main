package com.deeprag.chunker;

import com.deeprag.parser.ParseResult;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 文本块（Chunk）数据类
 * <p>
 * 文档经过 Chunker 切分后的基本单元，是 RAG 管线中向量化和检索的最小粒度。
 * 每个 Chunk 包含唯一 ID、文本内容和从原始文档继承的元数据。
 */
@Data
@AllArgsConstructor
public class Chunk {
    /** 块的唯一标识（由文件名+序号的 MD5 哈希生成） */
    private String id;
    /** 块的文本内容 */
    private String content;
    /** 元数据：包含来源文档标题、文件路径、章节列表等 */
    private Map<String, Object> metadata;

    /**
     * 从解析结果的元数据中提取基础元数据，用于构造 Chunk
     *
     * @param meta 文档解析结果中的元数据
     * @return 适合存入 Chunk 的元数据 Map
     */
    public static Map<String, Object> metadataFrom(ParseResult.Metadata meta) {
        return Map.of(
                "title", meta.getTitle(),
                "source", meta.getSource(),
                "sections", String.join("|", meta.getSections())
        );
    }
}

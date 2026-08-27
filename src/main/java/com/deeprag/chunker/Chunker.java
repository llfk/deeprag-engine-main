package com.deeprag.chunker;

import com.deeprag.parser.ParseResult;
import java.util.List;

/**
 * 文本分块器接口（策略模式）
 * <p>
 * 将解析后的文档内容切分为多个 Chunk，是 RAG 管线中"解析→分块→向量化"流程的第二步。
 * 不同实现提供不同的分块策略（固定大小、递归、语义、父子、结构感知等），
 * 通过策略模式可以在运行时灵活切换。
 */
public interface Chunker {
    /**
     * 对解析后的文档进行分块
     *
     * @param parseResult 文档解析结果，包含文本内容和元数据
     * @return 分块结果列表
     */
    List<Chunk> chunk(ParseResult parseResult);
}

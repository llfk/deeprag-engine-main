package com.deeprag.parser;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 文档解析结果数据类
 * <p>
 * 封装文档解析的输出，包含提取的文本内容和文档元数据。
 * 是 Parser → Chunker 管线的数据传递载体。
 */
@Data
@AllArgsConstructor
public class ParseResult {
    /** 提取的全文内容 */
    private String content;
    /** 文档元数据（标题、来源、章节列表） */
    private Metadata metadata;

    /**
     * 文档元数据
     */
    @Data
    @AllArgsConstructor
    public static class Metadata {
        /** 文档标题（Markdown 取第一个一级标题，PDF 取文件名） */
        private String title;
        /** 源文件路径 */
        private String source;
        /** 章节标题列表（仅 Markdown 可提取，PDF 为空） */
        private List<String> sections;
    }
}

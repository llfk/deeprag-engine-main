package com.deeprag.parser;

import java.nio.file.Path;

/**
 * 文档解析路由器（简单工厂模式）
 * <p>
 * 根据文件扩展名将解析请求分发到对应的解析器（Markdown / PDF），
 * 是 RAG 管线中"解析→分块→向量化"流程的第一步。
 */
public class ParserRouter {

    private final MarkdownParser markdownParser = new MarkdownParser();
    private final PdfParser pdfParser = new PdfParser();

    /**
     * 根据文件扩展名选择合适的解析器并解析文档
     *
     * @param filePath 文件路径
     * @return 解析结果，包含文本内容和元数据
     * @throws IllegalArgumentException 不支持的文件格式
     */
    public ParseResult parse(String filePath) {
        String ext = getExtension(filePath);
        return switch (ext.toLowerCase()) {
            case "md", "markdown" -> markdownParser.parse(filePath);
            case "pdf" -> pdfParser.parse(filePath);
            default -> throw new IllegalArgumentException("不支持的文件格式: " + ext);
        };
    }

    /** 判断是否支持该文件格式的解析 */
    public boolean supports(String filePath) {
        String ext = getExtension(filePath);
        return ext.equals("md") || ext.equals("markdown") || ext.equals("pdf");
    }

    /** 从文件路径中提取扩展名 */
    private String getExtension(String filePath) {
        String name = Path.of(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }
}

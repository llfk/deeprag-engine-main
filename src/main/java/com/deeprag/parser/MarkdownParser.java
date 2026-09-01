package com.deeprag.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 文档解析器
 * <p>
 * 解析 Markdown 文件，提取全文内容和章节结构信息。
 * 章节通过识别一级标题（# 标题）来确定，用于后续的结构感知分块。
 * 第一个一级标题同时作为文档标题。
 */
public class MarkdownParser {

    /**
     * 解析 Markdown 文件
     *
     * @param filePath Markdown 文件路径
     * @return 解析结果，包含全文内容、标题和章节列表
     */
    public ParseResult parse(String filePath) {
        try {
            // 读取整个文件内容为字符串
            String content = Files.readString(Path.of(filePath));

            // 提取所有一级标题作为章节列表
            List<String> sections = new ArrayList<>();
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ")) {
                    sections.add(trimmed.substring(2).trim());
                }
            }

            // 第一个一级标题作为文档标题，没有则用文件名
            String title = sections.isEmpty()
                    ? Path.of(filePath).getFileName().toString()
                    : sections.get(0);

            return new ParseResult(content, new ParseResult.Metadata(title, filePath, sections));
        } catch (IOException e) {
            throw new RuntimeException("解析 Markdown 失败: " + filePath, e);
        }
    }
}

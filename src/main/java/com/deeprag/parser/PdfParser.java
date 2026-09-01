package com.deeprag.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * PDF 文档解析器
 * <p>
 * 使用 Apache PDFBox 逐页提取 PDF 文件的文本内容。
 * 注意：PDF 解析通常无法提取章节结构信息（sections 为空），
 * 因此后续分块会回退到固定大小或递归分块策略。
 */
public class PdfParser {

    /**
     * 解析 PDF 文件，逐页提取文本
     *
     * @param filePath PDF 文件路径
     * @return 解析结果，包含全文内容（无章节结构信息）
     */
    public ParseResult parse(String filePath) {
        // 使用 PDFBox 的Loader.loadPDF()加载 PDF 文件并提取文本
        try (PDDocument doc = Loader.loadPDF(new File(filePath))) {
            // 使用PDFBox 提供的 PDFTextStripper 提取文本
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();

            // 逐页提取文本，页间用换行分隔
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(doc);
                text.append(pageText);
                if (i < doc.getNumberOfPages()) {
                    text.append("\n");
                }
            }

            // PDF 通常无法提取标题，使用文件名作为标题
            String title = Path.of(filePath).getFileName().toString();
            return new ParseResult(text.toString(), new ParseResult.Metadata(title, filePath, List.of()));
        } catch (Exception e) {
            throw new RuntimeException("解析 PDF 失败: " + filePath, e);
        }
    }
}

package com.deeprag.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文档解析器
 * <p>
 * 由 {@link PdfLayoutStripper} 逐页提取文本并剔除页眉、页脚、页码与水印；
 * 对没有文本层的页面（扫描页），渲染成图片后交给 {@link OcrTextExtractor} 识别。
 * <p>
 * 判断粒度是「逐页」而不是「整篇」：一份文档里混着几页扫描图很常见，
 * 若按整篇字符数一刀切，这类页面的内容会被静默丢掉。
 * <p>
 * 注意：PDF 解析通常无法提取章节结构信息（sections 为空），
 * 因此后续分块会回退到固定大小或递归分块策略。
 */
public class PdfParser {

    /**
     * 每页最少可提取的非空白字符数，低于该值判定为「这一页没有文本层」（疑似扫描页或图片页）
     */
    private static final int MIN_CHARS_PER_PAGE = 20;

    /**
     * 扫描页渲染成图片时的分辨率。
     * 中文小字在 200dpi 下识别率明显下降，所以统一用 300dpi。
     */
    private static final int OCR_DPI = 300;

    /**
     * 解析 PDF 文件，逐页提取文本；没有文本层的页用 OCR 补
     *
     * @param filePath PDF 文件路径
     * @return 解析结果，包含全文内容（无章节结构信息）
     */
    public ParseResult parse(String filePath) {
        PDDocument doc;
        try {
            doc = Loader.loadPDF(new File(filePath));
        } catch (IOException e) {
            throw new RuntimeException("加载 PDF 失败: " + filePath, e);
        }

        try (doc) {
            int pageCount = doc.getNumberOfPages();
            String content = extractText(doc, filePath);

            // 空文本体检：扫描件/图片型 PDF 没有文本层，提取结果几乎是空白。
            // 这类文档如果放行，会在分块阶段得到 0 个 chunk，最终以「索引越界」之类与真实原因无关的报错暴露出来。
            long visibleChars = countVisible(content);
            if (visibleChars < (long) pageCount * MIN_CHARS_PER_PAGE) {
                throw new IllegalStateException("PDF 无可提取文本层（疑似扫描件或图片型 PDF），需 OCR 后才能索引: "
                        + filePath + "（共 " + pageCount + " 页，仅提取到 " + visibleChars + " 个非空白字符）");
            }

            // PDF 通常无法提取标题，使用文件名作为标题
            String title = Path.of(filePath).getFileName().toString();
            return new ParseResult(content, new ParseResult.Metadata(title, filePath, List.of()));
        } catch (IOException e) {
            throw new RuntimeException("解析 PDF 失败: " + filePath, e);
        }
    }

    /**
     * 逐页提取正文：有文本层的页直接用 PDFBox 的结果，没有文本层的页渲染后交给 OCR
     */
    private String extractText(PDDocument doc, String filePath) throws IOException {
        List<String> textLayer = new PdfLayoutStripper().extractPages(doc);
        List<String> pages = new ArrayList<>(textLayer.size());
        PDFRenderer renderer = null;
        int scannedPages = 0;

        for (int i = 0; i < textLayer.size(); i++) {
            String pageText = textLayer.get(i);
            if (countVisible(pageText) >= MIN_CHARS_PER_PAGE) {
                pages.add(pageText);
                continue;
            }

            // 这一页没有文本层：交给 OCR。
            // 有文本层的页绝不走 OCR——文字层是精确的，OCR 有错误率，而且更慢。
            scannedPages++;
            if (!OcrTextExtractor.available()) {
                // 先占位，循环结束后统一报错，这样异常信息里能带上准确的扫描页数
                pages.add("");
                continue;
            }
            if (renderer == null) {
                renderer = new PDFRenderer(doc);
            }
            BufferedImage image = renderer.renderImageWithDPI(i, OCR_DPI);
            pages.add(OcrTextExtractor.extract(image));
        }

        if (scannedPages > 0 && !OcrTextExtractor.available()) {
            throw new IllegalStateException("PDF 有 " + scannedPages + " 页没有文本层，且 OCR 不可用"
                    + "（RapidOCR 原生库无法在当前平台加载）: " + filePath);
        }
        return String.join("\n", pages);
    }

    /** 统计非空白字符数 */
    private static long countVisible(String text) {
        return text.chars().filter(c -> !Character.isWhitespace(c)).count();
    }
}

package com.deeprag.parser;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word（.docx）文档解析器
 * <p>
 * 与 PDF 不同，docx 的结构是显式声明的：标题层级写在段落样式的名称里，段落边界就是一个个
 * {@code <w:p>}，不需要从排版（字号、字体、行距）反推。因此这里没有任何启发式规则——
 * 没有字号聚类、没有标点过滤、没有行宽判断，那些都是为了「从版式猜语义」才存在的。
 * <p>
 * 产出统一为 Markdown：标题转成 ATX 形式（{@code # 标题}），段落之间以换行分隔。
 * {@link StructureAwareChunker} 只认正文里的 {@code #}，所以下游无需为 Word 单独适配。
 * <p>
 * 只支持 .docx（OOXML）。97-2003 的 .doc 是二进制格式，POI 对应的 HWPF 对样式支持有限，不在支持范围。
 */
public class WordParser {

    /**
     * 标题样式名：英文模板为 {@code "heading 1"}，中文模板为 {@code "标题 1"}
     * <p>
     * 只能靠显示名判断层级。实测样式 ID 是 {@code 2}/{@code 3}/{@code 4} 这样的数字，
     * 直接匹配 {@code Heading1} 会全部落空。
     */
    private static final Pattern HEADING_STYLE =
            Pattern.compile("(?:heading|标题)\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    /** 标题层级上限，与 Markdown ATX 语法及 StructureAwareChunker 保持一致 */
    private static final int MAX_HEADING_LEVEL = 6;

    /**
     * 解析 docx 文件
     *
     * @param filePath docx 文件路径
     * @return 解析结果，内容为 Markdown 形式的文本，并带章节结构
     */
    public ParseResult parse(String filePath) {
        String content;
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(filePath))) {
            content = toMarkdown(doc);
        } catch (IOException e) {
            throw new RuntimeException("解析 Word 文档失败: " + filePath, e);
        }

        // 产出本身就是 Markdown，直接复用 Markdown 的标题扫描，不必再写一套
        List<MarkdownParser.Heading> headings = MarkdownParser.scanHeadings(content);
        List<String> sections = new ArrayList<>(headings.size());
        String title = null;
        for (MarkdownParser.Heading heading : headings) {
            sections.add(heading.title());
            // 第一个非空一级标题作为文档标题，没有则用文件名
            if (title == null && heading.level() == 1 && !heading.title().isBlank()) {
                title = heading.title();
            }
        }
        if (title == null) {
            title = Path.of(filePath).getFileName().toString();
        }
        return new ParseResult(content, new ParseResult.Metadata(title, filePath, sections));
    }

    /** 把正文元素转成 Markdown：标题加 ATX 前缀，其余段落原样输出，段落之间以换行分隔 */
    private static String toMarkdown(XWPFDocument doc) {
        StringBuilder markdown = new StringBuilder();
        for (IBodyElement element : doc.getBodyElements()) {
            if (!(element instanceof XWPFParagraph paragraph)) {
                continue;   // 表格暂不处理
            }
            String text = paragraph.getText();
            if (text == null || text.isBlank()) {
                continue;   // 空段落只起排版作用，转成 Markdown 会留下成串空行
            }
            // 段内的软换行与制表符要压平：本类用换行分隔段落，段内再出现换行会把一段拆成多段
            text = text.replaceAll("\\s+", " ").strip();

            if (markdown.length() > 0) {
                markdown.append('\n');
            }
            int level = headingLevel(doc, paragraph);
            if (level > 0) {
                markdown.append("#".repeat(level)).append(' ');
            } else if (text.startsWith("#")) {
                // 正文若以 # 开头，下游的 ATX 扫描会误判为标题，转义掉
                text = "\\" + text;
            }
            markdown.append(text);
        }
        return markdown.toString();
    }

    /**
     * 从段落样式解析标题层级，返回 0 表示正文
     * <p>
     * 这里不读 {@code outlineLvl}（大纲级别）：实测一份真实文档的 9 个标题里只有 1 个设置了它，
     * 其余都是空的，靠它会把大多数标题漏掉。
     */
    private static int headingLevel(XWPFDocument doc, XWPFParagraph paragraph) {
        String styleId = paragraph.getStyle();
        if (styleId == null) {
            return 0;   // 没有显式样式即正文，实测 70 段里有 61 段如此
        }
        XWPFStyle style = doc.getStyles().getStyle(styleId);
        if (style == null || style.getName() == null) {
            return 0;
        }
        Matcher matcher = HEADING_STYLE.matcher(style.getName());
        return matcher.matches() ? Math.min(Integer.parseInt(matcher.group(1)), MAX_HEADING_LEVEL) : 0;
    }
}

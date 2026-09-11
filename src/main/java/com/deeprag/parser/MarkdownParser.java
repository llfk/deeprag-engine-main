package com.deeprag.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 文档解析器
 * <p>
 * 解析 Markdown 文件，提取全文内容和章节结构信息。
 * 章节结构通过扫描 ATX 标题（# 到 ######）得到，各层级标题都会被识别；
 * 围栏代码块（``` 或 ~~~）内、缩进代码块内以及 front-matter 元数据区间内的行
 * 不参与标题识别，因此 shell 注释、YAML 注释形式的 {@code # 注释} 不会被误判为章节。
 * 文件编码按 UTF-8 优先、GB18030 回退的方式读取，并自动剥离 BOM。
 * <p>
 * 章节以偏移量的形式记录，供后续结构感知分块按位置精确切分，
 * 避免按标题文本查找导致的错位。第一个一级标题同时作为文档标题。
 */
public class MarkdownParser {

    /** ATX 标题的最大层级 */
    private static final int MAX_HEADING_LEVEL = 6;

    /** 围栏代码块的最小围栏字符数 */
    private static final int MIN_FENCE_LENGTH = 3;

    /** front-matter 区间的最大行数，超过则视为普通分隔线，避免误吞正文 */
    private static final int MAX_FRONT_MATTER_LINES = 50;

    /** 非 UTF-8 文档的回退编码（GB18030 是 GBK/GB2312 的超集） */
    private static final Charset FALLBACK_CHARSET = Charset.forName("GB18030");

    /**
     * 章节节点
     *
     * @param level              标题层级（1-6）
     * @param title              标题文本（已去掉 # 前缀与尾部闭合序列）
     * @param startOffset        标题行在全文中的起始偏移
     * @param contentStartOffset 标题之后正文的起始偏移
     * @param endOffset          章节结束偏移（下一个标题的起始偏移，没有下一个则为全文末尾）
     */
    public record Heading(int level, String title, int startOffset, int contentStartOffset, int endOffset) {
    }

    /**
     * 解析 Markdown 文件
     *
     * @param filePath Markdown 文件路径
     * @return 解析结果，包含全文内容、标题和章节列表
     */
    public ParseResult parse(String filePath) {
        try {
            // 读取整个文件内容（自动识别 UTF-8 / GB18030，并剥离 BOM）
            String content = readText(Path.of(filePath));

            // 扫描章节结构：所有层级的标题，跳过代码块内的伪标题
            List<Heading> headings = scanHeadings(content);
            List<String> sections = new ArrayList<>(headings.size());
            for (Heading heading : headings) {
                sections.add(heading.title());
            }

            // 第一个非空一级标题作为文档标题，没有则用文件名
            String title = null;
            for (Heading heading : headings) {
                if (heading.level() == 1 && !heading.title().isBlank()) {
                    title = heading.title();
                    break;
                }
            }
            if (title == null) {
                title = Path.of(filePath).getFileName().toString();
            }

            return new ParseResult(content, new ParseResult.Metadata(title, filePath, sections));
        } catch (IOException e) {
            throw new RuntimeException("解析 Markdown 失败: " + filePath, e);
        }
    }

    /**
     * 扫描文档标题结构
     * <p>
     * 单遍扫描，逐行判断：
     * <ol>
     *   <li>围栏代码块（``` 或 ~~~，允许最多 3 个前导空格）内部的行全部忽略；</li>
     *   <li>缩进 4 个空格或制表符开头的行视为缩进代码块，忽略；</li>
     *   <li>其余行按 ATX 标题语法（# 到 ###### 后跟空白或行尾）识别为章节。</li>
     * </ol>
     * 每个章节的结束位置是下一个标题（任意层级）的起始位置。
     *
     * @param content 文档全文
     * @return 按出现顺序排列的章节列表，无章节时返回空列表
     */
    public static List<Heading> scanHeadings(String content) {
        List<int[]> marks = new ArrayList<>();      // 每项为 {level, startOffset, contentStartOffset}
        List<String> titles = new ArrayList<>();

        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;
        // front-matter 区间：值为此区间结束后的偏移，-1 表示当前不在区间内
        int frontMatterEndOffset = -1;
        boolean atDocumentStart = true;

        int length = content.length();
        int pos = 0;

        while (pos <= length) {
            // 定位当前行的范围（\n 之前，不含 \r）
            int lineEnd = content.indexOf('\n', pos);
            boolean lastLine = lineEnd < 0;
            if (lastLine) {
                lineEnd = length;
            }
            int nextPos = lastLine ? length : lineEnd + 1;

            int lineStop = lineEnd;
            if (lineStop > pos && content.charAt(lineStop - 1) == '\r') {
                lineStop--;
            }
            String line = content.substring(pos, lineStop);
            // 首行可能带 UTF-8 BOM，剥离后再识别标题
            if (pos == 0 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }

            boolean skipLine = false;

            if (!inFence) {
                String trimmedLine = line.trim();
                if (pos < frontMatterEndOffset) {
                    // front-matter 区间内的行（含 YAML 注释 # xxx）不参与标题识别
                    skipLine = true;
                } else if (atDocumentStart && !trimmedLine.isEmpty()) {
                    atDocumentStart = false;
                    if (isFrontMatterStart(trimmedLine)) {
                        // 仅当区间能在有限行内闭合时才认定为 front-matter，否则按普通分隔线处理
                        int endOffset = findFrontMatterEnd(content, pos);
                        if (endOffset > 0) {
                            frontMatterEndOffset = endOffset;
                            skipLine = true;
                        }
                    }
                }
            }

            if (!skipLine) {
                // 行首缩进：4 个及以上空格或制表符开头视为缩进代码块
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                boolean indentedCode = indent > 3
                        || (indent < line.length() && line.charAt(indent) == '\t');

                if (inFence) {
                    // 围栏内部：只判断是否遇到结束围栏
                    if (!indentedCode && isFenceClose(line, indent, fenceChar, fenceLength)) {
                        inFence = false;
                    }
                } else if (!indentedCode) {
                    int fenceRun = fenceRunLength(line, indent);
                    if (fenceRun >= MIN_FENCE_LENGTH) {
                        // 开启围栏代码块
                        inFence = true;
                        fenceChar = line.charAt(indent);
                        fenceLength = fenceRun;
                    } else {
                        HeadingMatch match = matchHeading(line, indent);
                        if (match != null) {
                            marks.add(new int[]{match.level(), pos, nextPos});
                            titles.add(match.title());
                        }
                    }
                }
            }

            if (lastLine) {
                break;
            }
            pos = nextPos;
        }

        // 章节结束位置：下一个标题的起始位置，最后一个章节到全文末尾
        List<Heading> headings = new ArrayList<>(titles.size());
        for (int i = 0; i < titles.size(); i++) {
            int[] mark = marks.get(i);
            int end = (i + 1 < titles.size()) ? marks.get(i + 1)[1] : length;
            headings.add(new Heading(mark[0], titles.get(i), mark[1], mark[2], end));
        }
        return headings;
    }

    /**
     * 读取文本文件内容
     * <p>
     * 先按 UTF-8 严格解码；遇到非法字节序列（国内常见的 GBK/GB18030 文档）时
     * 回退到 GB18030 解码，而不是直接抛出异常导致整篇文档无法索引。
     * 解码后剥离可能存在的 UTF-8 BOM，避免第一个标题因行首的不可见字符
     * （U+FEFF）而识别失败、导致文档标题退化成文件名。
     *
     * @param path 文件路径
     * @return 文件文本内容
     * @throws IOException 读取失败
     */
    private static String readText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);

        String text;
        try {
            // 严格模式：遇到非法字节序列就报错，而不是悄悄替换成 U+FFFD
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // 非 UTF-8 编码（GBK/GB2312 等），按 GB18030 解码
            text = new String(bytes, FALLBACK_CHARSET);
        }

        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    /**
     * 探测 front-matter 区间
     * <p>
     * 从起始标记所在行之后开始查找结束标记（--- 或 ...），最多查找
     * {@link #MAX_FRONT_MATTER_LINES} 行；找不到闭合标记则返回 -1，
     * 此时起始行按普通分隔线处理，避免把正文误当成元数据整段吞掉。
     *
     * @param content     文档全文
     * @param startOffset 起始标记所在行的偏移
     * @return 区间结束之后的偏移；未能闭合时返回 -1
     */
    private static int findFrontMatterEnd(String content, int startOffset) {
        int pos = content.indexOf('\n', startOffset);
        if (pos < 0) {
            return -1;
        }
        pos++;

        for (int line = 0; line < MAX_FRONT_MATTER_LINES; line++) {
            int lineEnd = content.indexOf('\n', pos);
            boolean lastLine = lineEnd < 0;
            if (lastLine) {
                lineEnd = content.length();
            }
            int stop = lineEnd;
            if (stop > pos && content.charAt(stop - 1) == '\r') {
                stop--;
            }
            if (pos <= content.length() && isFrontMatterEnd(content.substring(pos, stop).trim())) {
                return lastLine ? content.length() : lineEnd + 1;
            }
            if (lastLine) {
                break;
            }
            pos = lineEnd + 1;
        }
        return -1;
    }

    /** front-matter 起始标记：文档开头的独立 --- 行 */
    private static boolean isFrontMatterStart(String trimmedLine) {
        return trimmedLine.equals("---");
    }

    /** front-matter 结束标记：--- 或 YAML 文档结束标记 ... */
    private static boolean isFrontMatterEnd(String trimmedLine) {
        return trimmedLine.equals("---") || trimmedLine.equals("...");
    }

    /** 匹配 ATX 标题，命中返回层级与标题文本，否则返回 null */
    private static HeadingMatch matchHeading(String line, int indent) {
        int level = 0;
        while (indent + level < line.length()
                && line.charAt(indent + level) == '#'
                && level < MAX_HEADING_LEVEL) {
            level++;
        }
        if (level == 0) {
            return null;
        }

        int after = indent + level;
        // # 之后必须是空白或行尾，否则不是标题（如 7 个 # 或 #include）
        if (after < line.length() && line.charAt(after) != ' ' && line.charAt(after) != '\t') {
            return null;
        }

        String title = line.substring(after).trim();
        // 去掉尾部闭合序列，如 "## 标题 ##"
        title = title.replaceAll("[ \t]+#+$", "").trim();
        return new HeadingMatch(level, title);
    }

    /** 统计行首（缩进之后）连续出现的围栏字符个数，非围栏字符返回 0 */
    private static int fenceRunLength(String line, int indent) {
        if (indent >= line.length()) {
            return 0;
        }
        char c = line.charAt(indent);
        if (c != '`' && c != '~') {
            return 0;
        }
        int run = 0;
        while (indent + run < line.length() && line.charAt(indent + run) == c) {
            run++;
        }
        return run;
    }

    /** 判断是否为结束围栏：字符与开启围栏一致、长度不小于开启围栏、之后只有空白 */
    private static boolean isFenceClose(String line, int indent, char fenceChar, int fenceLength) {
        if (indent >= line.length() || line.charAt(indent) != fenceChar) {
            return false;
        }
        int run = fenceRunLength(line, indent);
        if (run < fenceLength) {
            return false;
        }
        return line.substring(indent + run).trim().isEmpty();
    }

    /** 标题匹配结果 */
    private record HeadingMatch(int level, String title) {
    }
}

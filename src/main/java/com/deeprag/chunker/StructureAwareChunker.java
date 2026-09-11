package com.deeprag.chunker;

import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.MarkdownParser;
import com.deeprag.parser.ParseResult;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构感知分块器：按文档结构（sections）切分
 * <p>
 * 每个章节作为一个独立块，章节内容为「标题行 + 该标题的直系正文」，
 * 直系正文到下一个任意层级的标题为止（子标题的内容由其自身成块）。
 * 章节位置由 {@link MarkdownParser#scanHeadings(String)} 给出的偏移量确定，
 * 不按标题文本查找，因此重复标题、标题文本同时出现在正文或代码块中都不会错位。
 * <p>
 * 每个块都会带上标题路径面包屑（如「文档标题 &gt; 章 &gt; 节」）：
 * 首行以【】形式写入块内容（使结构信息参与向量化与检索），
 * 同时写入 metadata 的 headingPath 字段，便于引用溯源。
 * 面包屑占用块长预算，超长章节按扣减后的预算进行二次切分。
 */
public class StructureAwareChunker implements Chunker {

    /** 标题层级上限，与 MarkdownParser 的 ATX 标题层级保持一致 */
    private static final int MAX_HEADING_LEVEL = 6;

    /** 二次切分时给章节正文保留的最小预算（字符） */
    private static final int MIN_SECTION_BUDGET = 200;

    /** 面包屑包装的固定开销（【】与空行） */
    private static final int BREADCRUMB_OVERHEAD = 4;

    private final int maxSize;
    private final int overlap;

    public StructureAwareChunker(int maxSize, int overlap) {
        this.maxSize = maxSize;
        this.overlap = overlap;
    }

    @Override
    public List<Chunk> chunk(ParseResult parseResult) {
        String text = parseResult.getContent();
        Map<String, Object> baseMeta = Chunk.metadataFrom(parseResult.getMetadata());
        List<String> sections = parseResult.getMetadata().getSections();

        ConsoleLog.info("结构感知分块开始，文本长度: " + text.length());

        // 非 Markdown 文档（解析器未提取到章节结构）不臆造结构，直接回退固定大小分块
        List<MarkdownParser.Heading> headings = sections.isEmpty()
                ? List.of()
                : MarkdownParser.scanHeadings(text);

        ConsoleLog.step("检测到 " + headings.size() + " 个章节");

        if (headings.isEmpty()) {
            // 没有章节信息，回退到固定大小分块
            ConsoleLog.dim("未检测到章节结构，回退到固定大小分块");
            return new FixedSizeChunker(maxSize, overlap).chunk(parseResult);
        }

        List<Chunk> chunks = new ArrayList<>();
        int globalIndex = 0;
        // 标题路径栈：pathByLevel[level] 记录当前各层级的标题，用于生成面包屑
        String[] pathByLevel = new String[MAX_HEADING_LEVEL + 1];

        for (int i = 0; i < headings.size(); i++) {
            MarkdownParser.Heading heading = headings.get(i);
            String sectionTitle = heading.title();

            // 先更新路径栈：即使本章节没有正文，其标题也要出现在子章节的面包屑里
            if (heading.level() >= 1 && heading.level() <= MAX_HEADING_LEVEL) {
                pathByLevel[heading.level()] = sectionTitle;
            }

            String sectionContent = extractSection(text, heading);

            if (sectionContent.isEmpty()) {
                continue;
            }

            // 面包屑：文档标题 > 章 > 节
            String breadcrumb = buildBreadcrumb(pathByLevel, heading.level());
            Map<String, Object> pathMeta = breadcrumb.isEmpty()
                    ? Map.of()
                    : Map.of("headingPath", breadcrumb);

            // 面包屑占用块长预算，扣减后再判断是否需要二次切分
            int budget = Math.max(MIN_SECTION_BUDGET, maxSize - breadcrumb.length() - BREADCRUMB_OVERHEAD);

            ConsoleLog.dim("处理章节 [" + (i + 1) + "/" + headings.size() + "] " + sectionTitle
                    + " (长度: " + sectionContent.length() + ")");

            if (sectionContent.length() <= budget) {
                // 章节未超限，直接作为一个块
                String id = generateId(parseResult.getMetadata().getSource(), globalIndex);

                Map<String, Object> meta = new HashMap<>(baseMeta);
                meta.put("sectionTitle", sectionTitle);
                meta.putAll(pathMeta);

                chunks.add(new Chunk(id, withBreadcrumb(breadcrumb, sectionContent), meta));
                globalIndex++;
            } else {
                // 章节超限，委托给 FixedSizeChunker 切分
                ConsoleLog.dim("  章节 \"" + sectionTitle + "\" 超过 maxSize，进行二次切分");

                // 构造一个临时 ParseResult 用于 FixedSizeChunker
                ParseResult sectionResult = new ParseResult(
                        sectionContent,
                        new ParseResult.Metadata(
                                parseResult.getMetadata().getTitle(),
                                parseResult.getMetadata().getSource(),
                                List.of(sectionTitle)
                        )
                );

                // 每个子块都要带面包屑，因此按扣减后的预算切分，保证加前缀后不超 maxSize
                int subOverlap = Math.min(overlap, budget / 4);
                List<Chunk> subChunks = new FixedSizeChunker(budget, subOverlap).chunk(sectionResult);

                // 为子块添加章节标题信息并重新生成 ID
                for (int j = 0; j < subChunks.size(); j++) {
                    Chunk sub = subChunks.get(j);
                    String id = generateId(parseResult.getMetadata().getSource(), globalIndex);

                    Map<String, Object> meta = new HashMap<>(sub.getMetadata());
                    meta.put("sectionTitle", sectionTitle);
                    meta.put("subChunkIndex", j);
                    meta.putAll(pathMeta);

                    chunks.add(new Chunk(id, withBreadcrumb(breadcrumb, sub.getContent()), meta));
                    globalIndex++;
                }
            }
        }

        ConsoleLog.step("结构感知分块完成，生成 " + chunks.size() + " 个块");
        return chunks;
    }

    /**
     * 按扫描得到的偏移量提取章节内容（标题行 + 直系正文）
     * <p>
     * 章节范围由 {@link MarkdownParser.Heading} 的偏移量给出，
     * 不做文本查找，因此不受重复标题或标题文本出现在正文中的影响。
     */
    private String extractSection(String text, MarkdownParser.Heading heading) {
        return text.substring(heading.startOffset(), heading.endOffset()).trim();
    }

    /**
     * 生成标题路径（面包屑）
     * <p>
     * 例：「RAG 系统最佳实践指南 &gt; 1. 分块策略选择 &gt; 1.1 FixedSize（固定大小分块）」。
     * 中间层级缺失时自动跳过，不会产生空片段。
     *
     * @param pathByLevel 各层级当前标题
     * @param level       当前章节层级
     * @return 以 " &gt; " 连接的标题路径，无法生成时返回空字符串
     */
    private String buildBreadcrumb(String[] pathByLevel, int level) {
        StringBuilder sb = new StringBuilder();
        for (int l = 1; l <= level && l < pathByLevel.length; l++) {
            String part = pathByLevel[l];
            if (part == null || part.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * 在块内容首行加上面包屑
     * <p>
     * 检索只对块内容做向量化，面包屑写在内容里才能让「所属章节」参与召回；
     * 超长章节二次切分后，每个子块也能保留自己在文档中的位置。
     */
    private String withBreadcrumb(String breadcrumb, String content) {
        if (breadcrumb.isEmpty()) {
            return content;
        }
        return "【" + breadcrumb + "】\n\n" + content;
    }

    private String generateId(String source, int index) {
        try {
            String raw = Path.of(source).getFileName().toString() + "_" + index;
            byte[] hash = MessageDigest.getInstance("MD5").digest(raw.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 12);
        } catch (Exception e) {
            return "chunk_" + index;
        }
    }
}

package com.deeprag.chunker;

import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.ParseResult;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构感知分块器：按文档结构（sections）切分
 * 每个 section 作为一个独立块
 * 超过 maxSize 的 section 委托给 FixedSizeChunker 进行二次切分
 */
public class StructureAwareChunker implements Chunker {

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
        ConsoleLog.step("检测到 " + sections.size() + " 个章节");

        if (sections.isEmpty()) {
            // 没有章节信息，回退到固定大小分块
            ConsoleLog.dim("未检测到章节结构，回退到固定大小分块");
            return new FixedSizeChunker(maxSize, overlap).chunk(parseResult);
        }

        List<Chunk> chunks = new ArrayList<>();
        int globalIndex = 0;

        for (int i = 0; i < sections.size(); i++) {
            String sectionTitle = sections.get(i).trim();
            String sectionContent = extractSection(text, sections, i);

            if (sectionContent.trim().isEmpty()) {
                continue;
            }

            ConsoleLog.dim("处理章节 [" + (i + 1) + "/" + sections.size() + "] " + sectionTitle
                    + " (长度: " + sectionContent.length() + ")");

            if (sectionContent.length() <= maxSize) {
                // 章节未超限，直接作为一个块
                String id = generateId(parseResult.getMetadata().getSource(), globalIndex);

                Map<String, Object> meta = new HashMap<>(baseMeta);
                meta.put("sectionTitle", sectionTitle);

                chunks.add(new Chunk(id, sectionContent.trim(), meta));
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

                List<Chunk> subChunks = new FixedSizeChunker(maxSize, overlap).chunk(sectionResult);

                // 为子块添加章节标题信息并重新生成 ID
                for (int j = 0; j < subChunks.size(); j++) {
                    Chunk sub = subChunks.get(j);
                    String id = generateId(parseResult.getMetadata().getSource(), globalIndex);

                    Map<String, Object> meta = new HashMap<>(sub.getMetadata());
                    meta.put("sectionTitle", sectionTitle);
                    meta.put("subChunkIndex", j);

                    chunks.add(new Chunk(id, sub.getContent(), meta));
                    globalIndex++;
                }
            }
        }

        ConsoleLog.step("结构感知分块完成，生成 " + chunks.size() + " 个块");
        return chunks;
    }

    /**
     * 从原文中提取某个章节的内容
     * 按章节标题定位，内容到下一个章节标题为止
     */
    private String extractSection(String text, List<String> sections, int sectionIndex) {
        String title = sections.get(sectionIndex);

        // 在文本中查找章节标题的位置
        int titlePos = text.indexOf(title);
        if (titlePos == -1) {
            // 标题不在文本中（可能是 metadata 里的摘要），尝试模糊匹配
            // 回退：把标题本身加上后续到下一标题的内容作为章节内容
            return tryExtractByPosition(text, sections, sectionIndex);
        }

        int contentStart = titlePos + title.length();

        // 查找下一个章节标题的位置
        int contentEnd = text.length();
        for (int i = sectionIndex + 1; i < sections.size(); i++) {
            int nextPos = text.indexOf(sections.get(i), contentStart);
            if (nextPos != -1) {
                contentEnd = nextPos;
                break;
            }
        }

        // 返回标题 + 内容
        return text.substring(titlePos, contentEnd).trim();
    }

    /**
     * 当标题无法在文本中精确定位时，按位置顺序估算章节范围
     */
    private String tryExtractByPosition(String text, List<String> sections, int sectionIndex) {
        // 尝试在文本中按顺序找到每个标题
        List<Integer> positions = new ArrayList<>();
        int searchFrom = 0;

        for (String section : sections) {
            int pos = text.indexOf(section, searchFrom);
            if (pos != -1) {
                positions.add(pos);
                searchFrom = pos + section.length();
            } else {
                positions.add(-1);
            }
        }

        int startPos = positions.get(sectionIndex);
        if (startPos == -1) {
            // 完全找不到，返回空
            return "";
        }

        int endPos = text.length();
        for (int i = sectionIndex + 1; i < positions.size(); i++) {
            if (positions.get(i) > startPos) {
                endPos = positions.get(i);
                break;
            }
        }

        return text.substring(startPos, endPos).trim();
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

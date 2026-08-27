package com.deeprag.chunker;

import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.ParseResult;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 递归分块器：按分隔符优先级逐级切分
 * 优先使用大分隔符（段落），切不动再用小分隔符（句子、空格、字符）
 */
public class RecursiveChunker implements Chunker {

    // 分隔符优先级：从大到小。先尝试按段落切（\n\n），切不动再按句子切（。！？），最后按字符硬切
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", "！", "？", " ", ""};

    private final int maxSize;
    private final int overlap;

    public RecursiveChunker(int maxSize, int overlap) {
        this.maxSize = maxSize;
        this.overlap = overlap;
    }

    @Override
    public List<Chunk> chunk(ParseResult parseResult) {
        String text = parseResult.getContent();
        Map<String, Object> baseMeta = Chunk.metadataFrom(parseResult.getMetadata());

        ConsoleLog.info("递归分块开始，文本长度: " + text.length());
        List<String> pieces = recursiveSplit(text, 0);

        // 组装 Chunk，带 overlap
        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        String prevTail = "";

        for (String piece : pieces) {
            String content = (piece).trim();
            if (content.isEmpty()) continue;

            // 拼接上一段的尾部作为 overlap 上下文
            String finalContent;
            if (!prevTail.isEmpty() && index > 0) {
                finalContent = prevTail + content;
            } else {
                finalContent = content;
            }

            String id = generateId(parseResult.getMetadata().getSource(), index);
            chunks.add(new Chunk(id, finalContent, baseMeta));

            // 保留当前 chunk 末尾 overlap 长度的文本
            if (finalContent.length() > overlap) {
                prevTail = finalContent.substring(finalContent.length() - overlap);
            } else {
                prevTail = finalContent;
            }
            index++;
        }

        ConsoleLog.step("递归分块完成，生成 " + chunks.size() + " 个块");
        return chunks;
    }

    /**
     * 递归切分：从 separatorLevel 开始尝试分隔
     * 如果某段仍然超过 maxSize，用下一级分隔符继续切
     */
    private List<String> recursiveSplit(String text, int separatorLevel) {
        if (separatorLevel >= SEPARATORS.length) {
            // 所有分隔符都用完了，强制按 maxSize 切
            return hardSplit(text);
        }

        List<String> result = new ArrayList<>();
        String sep = SEPARATORS[separatorLevel];

        if (sep.isEmpty()) {
            // 最后兜底：按字符硬切
            return hardSplit(text);
        }

        // 按当前分隔符切分
        String[] parts = text.split(sep.equals("\n\n") ? "\n\\s*\n" : sep.length() == 1 ?
                escapeRegex(sep) : escapeRegex(sep));

        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            // 如果当前段落加入后不超过 maxSize，合并
            if (current.length() == 0) {
                current.append(trimmed);
            } else if (current.length() + sep.length() + trimmed.length() <= maxSize) {
                current.append(sep).append(trimmed);
            } else {
                // 当前段落已满，先保存
                result.add(current.toString());
                current = new StringBuilder(trimmed);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        // 对仍然过大的块，用下一级分隔符继续切
        List<String> finalResult = new ArrayList<>();
        for (String piece : result) {
            if (piece.length() > maxSize) {
                finalResult.addAll(recursiveSplit(piece, separatorLevel + 1));
            } else {
                finalResult.add(piece);
            }
        }

        return finalResult;
    }

    /**
     * 强制硬切：按 maxSize 长度切分，作为最终兜底
     */
    private List<String> hardSplit(String text) {
        List<String> result = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + maxSize, text.length());
            result.add(text.substring(pos, end));
            pos = end;
        }
        return result;
    }

    /**
     * 转义正则特殊字符
     */
    private String escapeRegex(String s) {
        return s.replaceAll("([\\\\\\[\\](){}.*+?^$|])", "\\\\$1");
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

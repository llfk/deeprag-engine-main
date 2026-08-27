package com.deeprag.chunker;

import com.deeprag.parser.ParseResult;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 固定大小分块器
 * <p>
 * 最简单的分块策略：按固定字符数切分文本，支持重叠（overlap）和句子边界感知。
 * overlap 机制确保相邻块之间有公共文本，避免关键信息被截断在块边界处。
 */
public class FixedSizeChunker implements Chunker {

    /** 每个块的最大字符数 */
    private final int maxSize;
    /** 相邻块之间的重叠字符数 */
    private final int overlap;

    public FixedSizeChunker(int maxSize, int overlap) {
        this.maxSize = maxSize;
        this.overlap = overlap;
    }

    /**
     * 执行固定大小分块
     * <p>
     * 从文本起始位置开始，每次取 maxSize 个字符作为一个块。
     * 切分时会尽量在句号或换行处断开，避免截断句子。
     * 相邻块之间有 overlap 个字符的重叠。
     */
    @Override
    public List<Chunk> chunk(ParseResult parseResult) {
        String text = parseResult.getContent();
        Map<String, Object> baseMeta = Chunk.metadataFrom(parseResult.getMetadata());
        List<Chunk> chunks = new ArrayList<>();

        int start = 0;
        int index = 0;

        while (start < text.length()) {
            int end = Math.min(start + maxSize, text.length());

            // 句子边界感知：在 [start, end] 范围内寻找最近的句号或换行符，避免在句子中间切断
            if (end < text.length()) {
                int lastPeriod = text.lastIndexOf('。', end);
                int lastNewline = text.lastIndexOf('\n', end);
                int splitPoint = Math.max(lastPeriod, lastNewline);
                if (splitPoint > start) {
                    end = splitPoint + 1;
                }
            }

            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                String id = generateId(parseResult.getMetadata().getSource(), index);
                chunks.add(new Chunk(id, chunkText, baseMeta));
            }

            // 下一个块的起点向前移动 overlap 个字符，实现块间重叠
            int nextStart = end - overlap;
            // 防止无限循环：确保每次至少前进 minAdvance 个字符
            int minAdvance = maxSize - overlap;
            if (minAdvance < 1) {
                minAdvance = 1;
            }
            if (nextStart < start + minAdvance) {
                nextStart = start + minAdvance;
            }
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
            index++;
        }

        return chunks;
    }

    /**
     * 基于 MD5 哈希生成块 ID，确保同一来源文件和序号对应唯一 ID
     */
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

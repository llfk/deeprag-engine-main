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
 * 父子分块器：两级分块策略
 * 父块（大）用于检索，子块（小）用于精确匹配
 * 子块通过 parentId 关联到父块
 */
public class ParentChildChunker implements Chunker {

    private final int maxSize;
    private final int overlap;
    private final int childSize;
    private final int childOverlap;

    public ParentChildChunker(int maxSize, int overlap, int childSize, int childOverlap) {
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.childSize = childSize;
        this.childOverlap = childOverlap;
    }

    @Override
    public List<Chunk> chunk(ParseResult parseResult) {
        String text = parseResult.getContent();
        Map<String, Object> baseMeta = Chunk.metadataFrom(parseResult.getMetadata());

        ConsoleLog.info("父子分块开始，文本长度: " + text.length());
        ConsoleLog.dim("父块 maxSize=" + maxSize + " overlap=" + overlap);
        ConsoleLog.dim("子块 childSize=" + childSize + " childOverlap=" + childOverlap);

        List<Chunk> allChunks = new ArrayList<>();

        // 1. 生成父块
        List<ParentPiece> parentPieces = splitParent(text);
        ConsoleLog.step("生成 " + parentPieces.size() + " 个父块");

        for (int i = 0; i < parentPieces.size(); i++) {
            ParentPiece piece = parentPieces.get(i);
            String parentId = generateId(parseResult.getMetadata().getSource(), "p", i);

            // 父块 metadata：标记 isParent=true
            Map<String, Object> parentMeta = new HashMap<>(baseMeta);
            parentMeta.put("isParent", true);

            allChunks.add(new Chunk(parentId, piece.text, parentMeta));

            // 2. 在父块内生成子块
            List<String> childPieces = splitChild(piece.text);
            for (int j = 0; j < childPieces.size(); j++) {
                String childId = generateId(parseResult.getMetadata().getSource(), "c", i * 1000 + j);

                // 子块 metadata：标记 isParent=false，记录 parentId
                Map<String, Object> childMeta = new HashMap<>(baseMeta);
                childMeta.put("isParent", false);
                childMeta.put("parentId", parentId);

                allChunks.add(new Chunk(childId, childPieces.get(j), childMeta));
            }
        }

        ConsoleLog.step("父子分块完成，共 " + allChunks.size() + " 个块（含父子）");
        return allChunks;
    }

    /**
     * 按 maxSize + overlap 切分父块
     */
    private List<ParentPiece> splitParent(String text) {
        List<ParentPiece> pieces = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + maxSize, text.length());

            // 句子边界感知：在 [start+1, end] 范围内寻找最近的句号或换行符
            if (end < text.length()) {
                int splitPoint = findSplitPoint(text, start, end);
                if (splitPoint > start) {
                    end = splitPoint + 1;
                }
            }

            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                pieces.add(new ParentPiece(chunkText, start, end));
            }

            // 三级推进守卫：确保 start 严格递增，防止无限循环
            int nextStart = end - overlap;
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
        }

        return pieces;
    }

    /**
     * 在 [start+1, end] 范围内寻找最近的句号或换行符
     */
    private int findSplitPoint(String text, int start, int end) {
        int splitPoint = -1;
        for (int i = end; i > start; i--) {
            char c = text.charAt(i - 1);
            if (c == '。' || c == '\n') {
                splitPoint = i - 1;
                break;
            }
        }
        return splitPoint;
    }

    /**
     * 在父块文本内按 childSize + childOverlap 切分子块
     */
    private List<String> splitChild(String parentText) {
        List<String> pieces = new ArrayList<>();
        int start = 0;

        while (start < parentText.length()) {
            int end = Math.min(start + childSize, parentText.length());

            String chunkText = parentText.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                pieces.add(chunkText);
            }

            // 三级推进守卫：确保 start 严格递增，防止无限循环
            int nextStart = end - childOverlap;
            int minAdvance = childSize - childOverlap;
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
        }

        return pieces;
    }

    /**
     * 父块文本片段
     */
    private static class ParentPiece {
        final String text;
        final int start;
        final int end;

        ParentPiece(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    private String generateId(String source, String prefix, int index) {
        try {
            String raw = Path.of(source).getFileName().toString() + "_" + prefix + "_" + index;
            byte[] hash = MessageDigest.getInstance("MD5").digest(raw.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 12);
        } catch (Exception e) {
            return "chunk_" + prefix + "_" + index;
        }
    }
}

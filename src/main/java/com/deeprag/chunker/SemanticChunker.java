package com.deeprag.chunker;

import com.deeprag.embedding.EmbeddingService;
import com.deeprag.log.ConsoleLog;
import com.deeprag.parser.ParseResult;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语义分块器：基于句子间的 embedding 相似度来确定分块边界
 * 当相邻句子相似度低于阈值时，在此处切分
 */
public class SemanticChunker implements Chunker {

    private final EmbeddingService embeddingService;
    private final double similarityThreshold;
    private final int maxSize;
    private final int overlap;

    public SemanticChunker(EmbeddingService embeddingService, double similarityThreshold) {
        this(embeddingService, similarityThreshold, 800, 50);
    }

    public SemanticChunker(EmbeddingService embeddingService, double similarityThreshold,
                           int maxSize, int overlap) {
        this.embeddingService = embeddingService;
        this.similarityThreshold = similarityThreshold;
        this.maxSize = maxSize;
        this.overlap = overlap;
    }

    @Override
    public List<Chunk> chunk(ParseResult parseResult) {
        String text = parseResult.getContent();
        Map<String, Object> baseMeta = Chunk.metadataFrom(parseResult.getMetadata());

        ConsoleLog.info("语义分块开始，文本长度: " + text.length());

        // 1. 按句子切分
        List<String> sentences = splitSentences(text);
        ConsoleLog.step("切分出 " + sentences.size() + " 个句子");

        if (sentences.isEmpty()) {
            return List.of();
        }

        // 2. 批量获取 embedding
        ConsoleLog.step("获取句子 embedding...");
        List<float[]> embeddings = embeddingService.embedBatch(sentences);

        // 3. 计算相邻句子的余弦相似度
        List<Double> similarities = new ArrayList<>();
        for (int i = 0; i < embeddings.size() - 1; i++) {
            similarities.add(cosineSimilarity(embeddings.get(i), embeddings.get(i + 1)));
        }

        // 4. 在相似度低于阈值处切分
        List<List<String>> groups = new ArrayList<>();
        List<String> currentGroup = new ArrayList<>();
        currentGroup.add(sentences.get(0));

        for (int i = 0; i < similarities.size(); i++) {
            double sim = similarities.get(i);
            if (sim < similarityThreshold) {
                // 相似度低于阈值，在此处切分
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
            }
            currentGroup.add(sentences.get(i + 1));
        }
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }

        ConsoleLog.step("语义切分产生 " + groups.size() + " 个初始分组");

        // 5. 合并过小的分组，拆分过大的分组
        List<String> mergedChunks = mergeAndSplit(groups);
        ConsoleLog.step("合并/拆分后得到 " + mergedChunks.size() + " 个块");

        // 6. 组装 Chunk，带 overlap
        List<Chunk> chunks = new ArrayList<>();
        int index = 0;
        String prevTail = "";

        for (String chunkText : mergedChunks) {
            chunkText = chunkText.trim();
            if (chunkText.isEmpty()) continue;

            // 拼接上一段的尾部作为 overlap
            String finalContent;
            if (!prevTail.isEmpty() && index > 0) {
                finalContent = prevTail + chunkText;
            } else {
                finalContent = chunkText;
            }

            String id = generateId(parseResult.getMetadata().getSource(), index);
            chunks.add(new Chunk(id, finalContent, baseMeta));

            // 保留尾部 overlap
            if (finalContent.length() > overlap) {
                prevTail = finalContent.substring(finalContent.length() - overlap);
            } else {
                prevTail = finalContent;
            }
            index++;
        }

        ConsoleLog.step("语义分块完成，生成 " + chunks.size() + " 个块");
        return chunks;
    }

    /**
     * 按中文句号、感叹号、问号切分句子
     */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);

            // 遇到句子结束符号，切分
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                String s = sb.toString().trim();
                if (!s.isEmpty()) {
                    sentences.add(s);
                }
                sb = new StringBuilder();
            }
        }

        // 处理末尾没有结束符的文本
        String remaining = sb.toString().trim();
        if (!remaining.isEmpty()) {
            sentences.add(remaining);
        }

        return sentences;
    }

    /**
     * 合并过小的分组（<100字符），拆分过大的分组（>800字符）
     */
    private List<String> mergeAndSplit(List<List<String>> groups) {
        List<String> result = new ArrayList<>();

        // 合并小分组
        List<String> merged = new ArrayList<>();
        StringBuilder accumulator = new StringBuilder();

        for (List<String> group : groups) {
            String groupText = String.join("", group);

            if (groupText.length() < 100 && accumulator.length() + groupText.length() < maxSize) {
                accumulator.append(groupText);
            } else {
                if (accumulator.length() > 0) {
                    merged.add(accumulator.toString());
                    accumulator = new StringBuilder();
                }
                accumulator.append(groupText);
            }
        }
        if (accumulator.length() > 0) {
            merged.add(accumulator.toString());
        }

        // 拆分过大分组
        for (String chunk : merged) {
            if (chunk.length() > maxSize) {
                result.addAll(hardSplit(chunk));
            } else {
                result.add(chunk);
            }
        }

        return result;
    }

    /**
     * 硬切：按 maxSize 长度切分，尊重句子边界
     */
    private List<String> hardSplit(String text) {
        List<String> result = new ArrayList<>();
        int pos = 0;

        while (pos < text.length()) {
            int end = Math.min(pos + maxSize, text.length());

            // 尝试在句子边界处切分
            if (end < text.length()) {
                int lastSentenceEnd = -1;
                for (int i = end; i > pos; i--) {
                    char c = text.charAt(i - 1);
                    if (c == '。' || c == '！' || c == '？' || c == '\n') {
                        lastSentenceEnd = i;
                        break;
                    }
                }
                if (lastSentenceEnd > pos) {
                    end = lastSentenceEnd;
                }
            }

            result.add(text.substring(pos, end));
            pos = end;
        }

        return result;
    }

    /**
     * 计算余弦相似度
     * <p>
     * 公式：cos(A,B) = (A·B) / (||A|| * ||B||)
     * 结果范围 [-1, 1]，值越大表示两个向量方向越接近（语义越相似）。
     * 这里用于判断相邻句子的语义连贯性，低于阈值则在此处切分。
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
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

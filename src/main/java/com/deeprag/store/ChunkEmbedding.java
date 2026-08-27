package com.deeprag.store;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 文本块嵌入数据类，包含文本块的ID、原始内容、向量表示和元数据
 */
@Data
@AllArgsConstructor
public class ChunkEmbedding {
    private String chunkId;
    private String content;
    private float[] embedding;
    private Map<String, Object> metadata;
}

package com.deeprag.embedding;

import com.deeprag.config.DeepRagConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 文本向量化服务
 * <p>
 * 调用 Ollama 的 Embedding API 将文本转换为高维向量（如 nomic-embed-text 输出 768 维）。
 * 向量化是 RAG 管线中的关键步骤：将文本映射到语义空间，使得语义相近的文本
 * 在向量空间中距离也相近，从而支持相似度检索。
 * <p>
 * API 格式：POST {baseUrl}/api/embed，请求体 {"model": ..., "input": [text1, text2, ...]}
 */
public class EmbeddingService {

    private final String baseUrl;
    private final String model;
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingService(DeepRagConfig.EmbeddingConfig config) {
        this.baseUrl = config.getBaseUrl();
        this.model = config.getModel();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .build();
    }

    /** 对单条文本进行向量化，返回其向量表示 */
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.get(0);
    }

    /**
     * 批量向量化：一次 API 调用处理多条文本，比逐条调用更高效
     *
     * @param texts 待向量化的文本列表
     * @return 对应的向量列表，与输入顺序一一对应
     */
    public List<float[]> embedBatch(List<String> texts) {
        try {
            // 构建json请求体
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", texts
            ));

            // 构建HTTP请求
            Request request = new Request.Builder()
                    .url(baseUrl + "/api/embed")
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Embedding 请求失败: " + response.code());
                }

                String respBody = response.body().string();
                // 将 JSON 字符串解析为树形结构
                JsonNode root = mapper.readTree(respBody);
                JsonNode embeddings = root.get("embeddings");

                List<float[]> results = new ArrayList<>();
                for (JsonNode emb : embeddings) {
                    float[] vector = new float[emb.size()];
                    for (int i = 0; i < emb.size(); i++) {
                        vector[i] = (float) emb.get(i).asDouble();
                    }
                    results.add(vector);
                }
                return results;
            }
        } catch (IOException e) {
            throw new RuntimeException("Embedding 调用失败", e);
        }
    }
}

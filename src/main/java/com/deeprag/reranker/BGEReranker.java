package com.deeprag.reranker;

import com.deeprag.log.ConsoleLog;
import com.deeprag.store.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

/**
 * 基于 BGE-Reranker 模型的重排序器实现
 * <p>
 * 通过 Ollama API 调用 BGE-Reranker 模型，对检索候选结果进行精排。
 * 支持优雅降级：当重排序服务不可用时，返回原始候选结果。
 * <p>
 * Ollama reranker API：POST {baseUrl}/api/rerank
 * 请求体：{"model": model, "query": query, "documents": [doc1, doc2, ...]}
 * 响应体包含按相关性分数排序的结果列表
 */
public class BGEReranker implements Reranker {

    private final String baseUrl;
    private final String model;
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public BGEReranker(String baseUrl, String model, int timeout) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build();
        ConsoleLog.step("BGEReranker 初始化: model=" + model + ", baseUrl=" + baseUrl);
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            ConsoleLog.dim("BGEReranker: 候选列表为空，跳过重排序");
            return Collections.emptyList();
        }

        ConsoleLog.step("BGE 重排序开始: query=" + query + ", candidates=" + candidates.size());

        try {
            // 提取所有候选文档内容
            List<String> documents = new ArrayList<>();
            for (SearchResult candidate : candidates) {
                documents.add(candidate.getContent());
            }

            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("query", query);
            requestBody.put("documents", documents);

            String bodyJson = mapper.writeValueAsString(requestBody);

            // 调用 Ollama reranker API
            Request request = new Request.Builder()
                    .url(baseUrl + "/api/rerank")
                    .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    ConsoleLog.warn("BGEReranker API 调用失败 (HTTP " + response.code()
                            + ")，请确认已拉取模型: ollama pull " + model);
                    return degrade(candidates, topK);
                }

                String respBody = response.body().string();
                return parseRerankResponse(respBody, candidates, topK);
            }

        } catch (IOException e) {
            ConsoleLog.error("BGEReranker 调用异常: " + e.getMessage()
                    + "，返回原始候选结果（优雅降级）");
            return degrade(candidates, topK);
        }
    }

    /**
     * 解析 Ollama reranker API 的响应
     * <p>
     * 预期响应格式：
     * {
     *   "results": [
     *     {"index": 0, "relevance_score": 0.95},
     *     {"index": 2, "relevance_score": 0.82},
     *     ...
     *   ]
     * }
     */
    private List<SearchResult> parseRerankResponse(String respBody,
                                                   List<SearchResult> candidates,
                                                   int topK) {
        try {
            JsonNode root = mapper.readTree(respBody);
            JsonNode resultsNode = root.get("results");

            if (resultsNode == null || !resultsNode.isArray()) {
                ConsoleLog.warn("BGEReranker 响应格式异常：缺少 results 数组，返回原始候选结果");
                return degrade(candidates, topK);
            }

            List<SearchResult> rerankedResults = new ArrayList<>();
            for (JsonNode resultNode : resultsNode) {
                int index = resultNode.get("index").asInt();
                float score = (float) resultNode.get("relevance_score").asDouble();

                if (index >= 0 && index < candidates.size()) {
                    SearchResult original = candidates.get(index);
                    // 用重排序分数替换原始分数
                    rerankedResults.add(new SearchResult(
                            original.getChunkId(),
                            original.getContent(),
                            score,
                            original.getMetadata()
                    ));
                }
            }

            // 按重排序分数降序排列
            rerankedResults.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

            // 截断到 topK
            if (rerankedResults.size() > topK) {
                rerankedResults = rerankedResults.subList(0, topK);
            }

            ConsoleLog.step("BGE 重排序完成，返回 " + rerankedResults.size() + " 条结果");
            return rerankedResults;

        } catch (Exception e) {
            ConsoleLog.error("BGEReranker 响应解析失败: " + e.getMessage() + "，返回原始候选结果");
            return degrade(candidates, topK);
        }
    }

    /**
     * 优雅降级：返回原始候选结果（截断到 topK）
     */
    private List<SearchResult> degrade(List<SearchResult> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}

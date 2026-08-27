package com.deeprag.reranker;

import com.deeprag.log.ConsoleLog;
import com.deeprag.store.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.*;

/**
 * 基于 LLM 的重排序器，通过 Ollama /api/chat 端点让 LLM 对候选文档打分。
 * Ollama 当前不支持 /api/rerank 端点，因此用通用 LLM 模拟交叉编码器行为。
 */
public class LLMReranker implements Reranker {

    private final String baseUrl;
    private final String model;
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public LLMReranker(String baseUrl, String model, int timeout) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build();
        ConsoleLog.step("LLMReranker 初始化: model=" + model + ", baseUrl=" + baseUrl);
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            ConsoleLog.dim("LLMReranker: 候选列表为空，跳过重排序");
            return Collections.emptyList();
        }

        ConsoleLog.step("LLM 重排序 " + candidates.size() + " 条候选文档 (模型: " + model + ")...");

        try {
            // 构建评分 prompt
            StringBuilder docBuilder = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                String content = candidates.get(i).getContent();
                if (content != null && content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                docBuilder.append("[").append(i + 1).append("] ").append(content).append("\n");
            }

            String prompt = "你是一个文档相关性评分专家。请评估以下每个文档与查询的相关性。\n\n"
                    + "查询: " + query + "\n\n"
                    + "文档:\n" + docBuilder + "\n"
                    + "请对每个文档给出 0.0 到 1.0 之间的相关性分数（0=完全不相关，1=完全匹配）。\n"
                    + "只输出 JSON 数组，不要输出其他内容。格式: [0.9, 0.3, 0.7, ...]";

            // 构建请求体
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("stream", false);

            String bodyJson = mapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/chat")
                    .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    ConsoleLog.warn("LLM 重排序 API 返回错误 (" + response.code() + ")，降级为原始排序");
                    return degrade(candidates, topK);
                }

                String respBody = response.body().string();
                JsonNode root = mapper.readTree(respBody);
                String content = root.path("message").path("content").asText("");

                double[] scores = parseScores(content, candidates.size());
                if (scores == null) {
                    ConsoleLog.warn("无法解析 LLM 评分结果，降级为原始排序");
                    return degrade(candidates, topK);
                }

                // 构建重排序结果
                List<SearchResult> reranked = new ArrayList<>();
                for (int i = 0; i < candidates.size(); i++) {
                    SearchResult original = candidates.get(i);
                    float score = (float) scores[i];
                    reranked.add(new SearchResult(
                            original.getChunkId(),
                            original.getContent(),
                            score,
                            original.getMetadata()
                    ));
                }

                reranked.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

                if (reranked.size() > topK) {
                    reranked = reranked.subList(0, topK);
                }

                ConsoleLog.step("LLM 重排序完成，返回 " + reranked.size() + " 条结果");
                return reranked;
            }

        } catch (IOException e) {
            ConsoleLog.warn("LLM 重排序请求失败，降级为原始排序: " + e.getMessage());
            return degrade(candidates, topK);
        }
    }

    /**
     * 从 LLM 输出中解析分数数组，支持 markdown 代码块、多余文本等容错处理。
     */
    private double[] parseScores(String content, int expectedLen) {
        if (content == null || content.isBlank()) return null;

        content = content.trim();

        // 去除 markdown 代码块包装
        Matcher codeBlockMatcher = Pattern.compile("(?s)```(?:json)?\\s*\n?(.*?)\n?```").matcher(content);
        if (codeBlockMatcher.find()) {
            content = codeBlockMatcher.group(1).trim();
        }

        // 提取 JSON 数组
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) return null;

        String jsonArray = content.substring(start, end + 1);

        List<Double> scores = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(jsonArray);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    scores.add(node.asDouble());
                }
            }
        } catch (Exception e) {
            // 逐个提取数字
            Matcher numMatcher = Pattern.compile("(\\d+\\.?\\d*)").matcher(jsonArray);
            while (numMatcher.find()) {
                try {
                    scores.add(Double.parseDouble(numMatcher.group(1)));
                } catch (NumberFormatException ignored) {}
            }
        }

        if (scores.isEmpty()) return null;

        if (scores.size() != expectedLen) {
            ConsoleLog.dim("LLM 返回 " + scores.size() + " 个分数，期望 " + expectedLen + " 个");
            while (scores.size() < expectedLen) scores.add(0.5);
            if (scores.size() > expectedLen) {
                scores = scores.subList(0, expectedLen);
            }
        }

        double[] result = new double[scores.size()];
        for (int i = 0; i < scores.size(); i++) result[i] = scores.get(i);
        return result;
    }

    private List<SearchResult> degrade(List<SearchResult> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}

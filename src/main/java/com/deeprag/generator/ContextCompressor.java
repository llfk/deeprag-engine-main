package com.deeprag.generator;

import com.deeprag.log.ConsoleLog;
import com.deeprag.store.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文压缩器（Stage 2）
 * <p>
 * 在 Reranking 之后对检索结果进行进一步压缩，控制输入给 LLM 的上下文长度：
 * <ol>
 *   <li>按分数阈值过滤低质量结果</li>
 *   <li>按分数降序排列</li>
 *   <li>按 Token 预算（maxContextChars）截断，优先保留高分结果</li>
 * </ol>
 * 目的：避免过多低质量上下文干扰 LLM 生成，同时控制 API 调用成本。
 */
public class ContextCompressor {

    /** 分数阈值：低于此分数的候选结果将被过滤 */
    private final double threshold;
    /** 上下文最大字符数：控制输入给 LLM 的总上下文长度 */
    private final int maxContextChars;

    public ContextCompressor(double threshold, int maxContextChars) {
        this.threshold = threshold;
        this.maxContextChars = maxContextChars;
    }

    /**
     * 压缩检索结果：过滤低分 + 按预算截断
     *
     * @param candidates Reranking 后的候选结果
     * @return 压缩后的结果列表
     */
    public List<SearchResult> compress(List<SearchResult> candidates) {
        if (candidates == null || candidates.isEmpty()) return candidates;

        // 第一步：按分数阈值过滤
        List<SearchResult> filtered = new ArrayList<>();
        for (SearchResult sr : candidates) {
            if (sr.getScore() >= threshold) {
                filtered.add(sr);
            }
        }

        // 兜底：如果所有候选都低于阈值，保留原始结果避免空上下文
        if (filtered.isEmpty()) {
            ConsoleLog.warn("上下文压缩: 所有候选低于阈值，保留原始结果");
            return candidates;
        }

        // 第二步：按分数降序排列，高分优先
        filtered.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());

        // 第三步：按字符预算截断，优先保留高分结果
        int totalChars = 0;
        List<SearchResult> result = new ArrayList<>();
        for (SearchResult sr : filtered) {
            if (totalChars + sr.getContent().length() > maxContextChars) break;
            totalChars += sr.getContent().length();
            result.add(sr);
        }

        ConsoleLog.step("上下文压缩: " + candidates.size() + " → " + result.size()
                + " 条 (阈值=" + threshold + ", 总长=" + totalChars + ")");
        return result;
    }
}

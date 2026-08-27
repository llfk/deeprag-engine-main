package com.deeprag.generator;

import com.deeprag.store.SearchResult;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 生成结果数据类
 */
@Data
@AllArgsConstructor
public class GenerationResult {
    /** LLM 生成的回答 */
    private String answer;
    /** 引用的文本块（Stage 1 暂不使用，为空列表） */
    private List<String> citedChunks;
    /** 幻觉检测分数（Stage 1 暂不使用，固定为 -1） */
    private float hallucinationScore;
    /** 检索到的相关文本块 */
    private List<SearchResult> retrievedChunks;
}

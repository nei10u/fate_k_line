package com.nei10u.fate.model;

import lombok.Data;

@Data
public class FateAnalysisReport {
    private Section overall;       // 命理总评
    private Section investment;    // 投资运势
    private Section career;        // 事业分析
    private Section wealth;        // 财富层级
    private Section love;          // 情感婚姻

    @Data
    public static class Section {
        private int score;         // 评分 0-10
        private String content;    // 详细文本 (支持 Markdown)
        private String summary;    // 一句话总结
    }
}

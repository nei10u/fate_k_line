package com.nei10u.fate.model;

import lombok.Data;
import java.util.List;

@Data
public class YearlyBatchResult {
    // AI 返回的列表
    private List<YearlyItem> items;

    @Data
    public static class YearlyItem {
        private int age;        // 岁数
        private int score;      // AI 认为的该年评分 (用于修正算法分数)
        private String content; // 具体的批断文本

        // 可选：直接由 LLM 产出的 K 线数值，若缺失则由后端补齐
        private Integer open;
        private Integer close;
        private String trend;

        // 可选：直接给出干支、大运，后端若缺失会回填
        private String ganZhi;
        private String daYun;
    }
}
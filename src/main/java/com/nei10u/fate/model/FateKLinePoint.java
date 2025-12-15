package com.nei10u.fate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FateKLinePoint {
    private int age;          // 岁数
    private int year;         // 年份
    private String ganZhi;    // 流年干支 (如 乙巳)
    private String daYun;     // 所属大运

    // K线数值
    private int score;        // 分数 (整合 Open/Close)
    private int open;
    private int close;
    private int high;
    private int low;

    private String trend;     // Bullish/Bearish

    // 新增：流年详细批注 (对应 PDF 中的 "运势批断")
    private String description;
}
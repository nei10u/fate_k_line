package com.nei10u.fate.model;

import lombok.Data;
import java.util.List;

@Data
public class FateResponse {
    private String requestId;
    private BaZiInfo baziInfo;
    private FateAnalysisReport analysisReport;
    private List<FateKLinePoint> kLineData;

    @Data
    public static class BaZiInfo {
        private String yearPillar;  // 年柱
        private String monthPillar; // 月柱
        private String dayPillar;   // 日柱
        private String hourPillar;  // 时柱
        private String solarTime;   // 真太阳时
        private String lunarDate;   // 农历文本
        private List<DaYunInfo> daYunList; // 大运列表
    }

    @Data
    public static class DaYunInfo {
        private int startAge;   // 起运年龄
        private int startYear;  // 起运年份
        private String ganZhi;  // 大运干支
    }
}
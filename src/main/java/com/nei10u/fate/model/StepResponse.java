package com.nei10u.fate.model;

import lombok.Data;

import java.util.List;

@Data
public class StepResponse {
    private String requestId;
    private FateResponse.BaZiInfo baziInfo;
    private FateAnalysisReport analysisReport;
    private List<YearlyBatchResult.YearlyItem> yearlyItems;
    private List<FateKLinePoint> kLineData;
}


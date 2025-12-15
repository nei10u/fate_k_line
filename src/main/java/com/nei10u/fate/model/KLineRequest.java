package com.nei10u.fate.model;

import lombok.Data;

import java.util.List;

@Data
public class KLineRequest {
    private FateRequest request;
    private List<YearlyBatchResult.YearlyItem> yearlyItems;
    private String requestId;
}


package com.nei10u.fate.model;

import lombok.Data;

@Data
public class FateRequest {
    private String requestId;
    private String name;
    private int year;
    private int month;
    private int day;
    private int hour;
    private int minute;
    private String gender; // "男" or "女"
    private String city;   // 出生地
    private Double longitude; // 经度，用于真太阳时计算，可选
}

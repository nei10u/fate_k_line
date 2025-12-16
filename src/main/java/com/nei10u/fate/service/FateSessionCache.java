package com.nei10u.fate.service;

import com.nei10u.fate.model.FateKLinePoint;
import com.nei10u.fate.model.FateResponse;
import com.nei10u.fate.model.YearlyBatchResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的进程内缓存：用于分步执行时在 /kline 与 /yearly 之间传递结果。
 * 以 requestId 为 key，避免二次调用 LLM。
 */
@Component
public class FateSessionCache {

    private static final Duration TTL = Duration.ofMinutes(30);
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>();

    /**
     * 仅更新 K 线相关结果（用于 /kline -> /yearly 的分步复用）。
     * baseline/bazi 等字段不会被覆盖。
     */
    public void upsertKline(String requestId, List<YearlyBatchResult.YearlyItem> yearlyItems, List<FateKLinePoint> kLineData) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        store.compute(requestId, (_k, old) -> {
            long now = System.currentTimeMillis();
            if (old == null || isExpired(old.createdAtMillis)) {
                return new CacheEntry(now, null, null, null, yearlyItems, kLineData);
            }
            return new CacheEntry(old.createdAtMillis, old.baziInfo, old.baseline, old.baselineAnalysis, yearlyItems, kLineData);
        });
    }

    /**
     * 更新 baseline（命格基线）与八字（供 /kline 直接复用，避免重复计算）。
     */
    public void upsertBaseline(String requestId, FateResponse.BaZiInfo baziInfo, Integer baseline, String baselineAnalysis) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        store.compute(requestId, (_k, old) -> {
            long now = System.currentTimeMillis();
            if (old == null || isExpired(old.createdAtMillis)) {
                return new CacheEntry(now, baziInfo, baseline, baselineAnalysis, null, null);
            }
            return new CacheEntry(old.createdAtMillis, baziInfo, baseline, baselineAnalysis, old.yearlyItems, old.kLineData);
        });
    }

    public Optional<CacheEntry> get(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        CacheEntry entry = store.get(requestId);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry.createdAtMillis)) {
            store.remove(requestId);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private boolean isExpired(long createdAtMillis) {
        long now = System.currentTimeMillis();
        return now - createdAtMillis > TTL.toMillis();
    }

    public record CacheEntry(long createdAtMillis,
                             FateResponse.BaZiInfo baziInfo,
                             Integer baseline,
                             String baselineAnalysis,
                             List<YearlyBatchResult.YearlyItem> yearlyItems,
                             List<FateKLinePoint> kLineData) {
    }
}

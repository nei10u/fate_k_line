package com.nei10u.fate.service;

import com.nei10u.fate.model.FateKLinePoint;
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

    public void put(String requestId, List<YearlyBatchResult.YearlyItem> yearlyItems, List<FateKLinePoint> kLineData) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        store.put(requestId, new CacheEntry(System.currentTimeMillis(), yearlyItems, kLineData));
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
            List<YearlyBatchResult.YearlyItem> yearlyItems,
            List<FateKLinePoint> kLineData) {
    }
}

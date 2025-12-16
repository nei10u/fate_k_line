package com.nei10u.fate.controller;

import com.nei10u.fate.model.FateAnalysisReport;
import com.nei10u.fate.model.FateKLinePoint;
import com.nei10u.fate.model.FateRequest;
import com.nei10u.fate.model.FateResponse;
import com.nei10u.fate.model.KLineRequest;
import com.nei10u.fate.model.StepResponse;
import com.nei10u.fate.model.YearlyBatchResult;
import com.nei10u.fate.service.FateAiService;
import com.nei10u.fate.service.FateSessionCache;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/fate")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FateController {

    private static final Logger log = LoggerFactory.getLogger(FateController.class);
    private final FateAiService fateAiService;
    private final FateSessionCache fateSessionCache;

    @PostMapping("/analyze")
    public ResponseEntity<FateResponse> analyze(@RequestBody FateRequest request) {
        return ResponseEntity.ok(fateAiService.analyze(request));
    }

    @PostMapping("/bazi")
    public ResponseEntity<StepResponse> bazi(@RequestBody FateRequest request) {
        String rid = ensureRequestId(request);
        log.info("[{}] step-bazi start", rid);
        FateResponse.BaZiInfo bazi = fateAiService.calculateBaZi(request);
        // 第一段：定盘 baseline（命格长期均值 μ）
        // 注意：baseline 不需要回传给前端（保持接口不变），但会写入缓存供 /kline 使用。
        FateAiService.BaselineResult baseline = fateAiService.generateBaseline(bazi, request.getGender(), rid);
        fateSessionCache.upsertBaseline(rid, bazi, baseline.getBaseline(), baseline.getAnalysis());
        log.info("[{}] step-bazi done", rid);
        StepResponse resp = new StepResponse();
        resp.setRequestId(rid);
        resp.setBaziInfo(bazi);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/report")
    public ResponseEntity<StepResponse> report(@RequestBody FateRequest request) {
        String rid = ensureRequestId(request);
        log.info("[{}] step-report start", rid);
        FateResponse.BaZiInfo bazi = fateAiService.calculateBaZi(request);
        FateAnalysisReport report = fateAiService.generateReport(bazi, request.getGender());
        log.info("[{}] step-report done", rid);
        StepResponse resp = new StepResponse();
        resp.setRequestId(rid);
        resp.setAnalysisReport(report);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/yearly")
    public ResponseEntity<StepResponse> yearly(@RequestBody FateRequest request) {
        String rid = ensureRequestId(request);
        log.info("[{}] step-yearly start (from cache)", rid);
        Optional<FateSessionCache.CacheEntry> cached = fateSessionCache.get(rid);
        if (cached.isEmpty()) {
            log.warn("[{}] step-yearly cache miss, require /kline first", rid);
            StepResponse resp = new StepResponse();
            resp.setRequestId(rid);
            return ResponseEntity.status(409).body(resp);
        }
        StepResponse resp = new StepResponse();
        resp.setRequestId(rid);
        // 前端表格展示使用 kLineData（包含 description），同时也带上 yearlyItems 备用
        resp.setKLineData(cached.get().kLineData());
        resp.setYearlyItems(cached.get().yearlyItems());
        log.info("[{}] step-yearly done size={}", rid,
                cached.get().yearlyItems() == null ? 0 : cached.get().yearlyItems().size());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/kline")
    public ResponseEntity<StepResponse> kline(@RequestBody KLineRequest payload) {
        FateRequest request = payload.getRequest();
        String rid = payload.getRequestId();
        if (rid == null || rid.isBlank()) {
            rid = ensureRequestId(request);
        } else {
            request.setRequestId(rid);
        }
        log.info("[{}] step-kline start (llm+build)", rid);
        // 优先复用 step1 缓存的 baseline/bazi，避免重复定盘
        Optional<FateSessionCache.CacheEntry> cached = fateSessionCache.get(rid);
        FateResponse.BaZiInfo bazi = cached.flatMap(e -> Optional.ofNullable(e.baziInfo()))
                .orElseGet(() -> fateAiService.calculateBaZi(request));
        Integer baseline = cached.map(FateSessionCache.CacheEntry::baseline).orElse(null);
        if (baseline == null) {
            // 兜底：若未先走 /bazi，也可在 /kline 内补定盘
            FateAiService.BaselineResult base = fateAiService.generateBaseline(bazi, request.getGender(), rid);
            baseline = base.getBaseline();
            fateSessionCache.upsertBaseline(rid, bazi, baseline, base.getAnalysis());
        }

        // 三段式：事实层 -> 规则层 -> 执行层（LLM 输出仍会被后端做“产品级兜底”后处理）
        List<YearlyBatchResult.YearlyItem> aiItems = payload.getYearlyItems();
        if (aiItems == null || aiItems.isEmpty()) {
            // 回到“一次性生成年度分数”的方案：模型只输出 score+content，K线连贯/红绿由后端派生
            aiItems = fateAiService.generateYearlyScoresOneShot(bazi, request.getGender(), baseline, rid);
        }
        List<FateKLinePoint> kLine = fateAiService.buildKLineFromYearlyScores(request.getYear(), bazi.getDaYunList(),
                aiItems, baseline);
        fateSessionCache.upsertKline(rid, aiItems, kLine);
        log.info("[{}] step-kline done size={}", rid, kLine.size());
        StepResponse resp = new StepResponse();
        resp.setRequestId(rid);
        resp.setKLineData(kLine);
        return ResponseEntity.ok(resp);
    }

    private String ensureRequestId(FateRequest request) {
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            request.setRequestId(UUID.randomUUID().toString());
        }
        return request.getRequestId();
    }
}

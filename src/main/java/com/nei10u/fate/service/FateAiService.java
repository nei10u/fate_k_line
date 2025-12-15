package com.nei10u.fate.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.nei10u.fate.model.FateAnalysisReport;
import com.nei10u.fate.model.FateKLinePoint;
import com.nei10u.fate.model.FateRequest;
import com.nei10u.fate.model.FateResponse;
import com.nei10u.fate.model.YearlyBatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FateAiService {
    private static final Logger log = LoggerFactory.getLogger(FateAiService.class);

    private static final JSONReader.Feature[] JSON_FEATURES = new JSONReader.Feature[]{
            JSONReader.Feature.SupportSmartMatch
    };

    private static final String REPORT_SCHEMA_HINT = """
            {
              "overall": {"score": 0, "content": "", "summary": ""},
              "investment": {"score": 0, "content": "", "summary": ""},
              "career": {"score": 0, "content": "", "summary": ""},
              "wealth": {"score": 0, "content": "", "summary": ""},
              "love": {"score": 0, "content": "", "summary": ""}
            }
            """;

    private final ChatClient chatClient;
    private final FateCalculationService calcService;

    public FateAiService(ChatClient.Builder builder, FateCalculationService calcService) {
        this.chatClient = builder.build();
        this.calcService = calcService;
    }

    /**
     * 兼容旧接口：一次性返回全量数据
     */
    public FateResponse analyze(FateRequest req) {
        String requestId = resolveRequestId(req);
        log.info("[{}] analyze start", requestId);
        FateResponse.BaZiInfo bazi = calculateBaZi(req);
        log.info("[{}] bazi calculated: {} {} {} {}", requestId, bazi.getYearPillar(), bazi.getMonthPillar(), bazi.getDayPillar(), bazi.getHourPillar());
        FateAnalysisReport report = generateReport(bazi, req.getGender());
        log.info("[{}] report generated", requestId);
        List<YearlyBatchResult.YearlyItem> yearlyItems = generateYearlyBatch(bazi, req.getGender(), requestId);
        log.info("[{}] yearly & kline raw items size={}", requestId, yearlyItems.size());
        List<FateKLinePoint> kLineData = buildKLine(req.getYear(), bazi.getDaYunList(), yearlyItems);
        log.info("[{}] kline built: {} points", requestId, kLineData.size());

        FateResponse response = new FateResponse();
        response.setRequestId(requestId);
        response.setBaziInfo(bazi);
        response.setAnalysisReport(report);
        response.setKLineData(kLineData);
        log.info("[{}] analyze done", requestId);
        return response;
    }

    /**
     * 仅计算八字与大运，供前端快速展示基础信息。
     */
    public FateResponse.BaZiInfo calculateBaZi(FateRequest req) {
        return calcService.calculate(req);
    }

    /**
     * 任务 A: 生成总体报告（使用 fastjson2 解析 LLM 输出）
     */
    public FateAnalysisReport generateReport(FateResponse.BaZiInfo bazi, String gender) {
        String prompt = String.format("""
                        你是一位精通《子平真诠》与现代金融的命理大师。
                        用户八字：%s %s %s %s (性别：%s)。

                        请生成一份结构化的【投资人生运势报告】。
                        要求：
                        1. 命理总评：分析格局高低，喜用神。
                        2. 投资/事业：结合 "偏财"、"七杀" 等十神心性，判断适合做 Holder 还是 Degen。
                        3. 情感婚姻简述。
                        4. 必须严格返回 JSON，不要包含 Markdown、额外引号或注释。

                        输出格式示例（严格遵守键名与结构）：
                        %s
                        """,
                bazi.getYearPillar(), bazi.getMonthPillar(), bazi.getDayPillar(), bazi.getHourPillar(), gender,
                REPORT_SCHEMA_HINT
        );

        try {
            String raw = chatClient.prompt().user(prompt).call().content();
            FateAnalysisReport parsed = parseWithFastjson(raw, FateAnalysisReport.class);
            return ensureSections(parsed);
        } catch (Exception e) {
            System.err.println("AI 报告生成失败: " + e.getMessage());
            return ensureSections(null);
        }
    }

    /**
     * 任务 B: 生成 0-80 岁流年详批（fastjson2 解析）
     */
    public List<YearlyBatchResult.YearlyItem> generateYearlyBatch(FateResponse.BaZiInfo bazi, String gender, String requestId) {
        String prompt = String.format("""
                        你是一位算命经验丰富的老先生。请为用户撰写【0-80岁流年详批全表】。
                        用户八字：%s %s %s %s (性别：%s)。
                        大运排盘：%s

                        任务要求：
                        1. 请生成 0岁 到 80岁 (共81条) 的流年运势分析。
                        2. **内容风格**：参考古籍与现代结合。必须指出具体的刑冲合害。
                           - 例如："丙午流年，子午冲，水火激战，需防心血管疾病或破财。"
                           - 例如："甲申流年，伤官见官，职场是非多。"
                        3. **评分**：请根据流年喜忌给出一个 0-100 的分数。
                        4. 每个流年同时给出 K 线四价与趋势：open/close/high/low/trend。若无法给出，请留空或与 score 一致，后端会补齐。
                        5. 观点一定要客观符合八字测算结论，无需修改数据。
                        6. 请返回 JSON 格式，包含 items 数组，禁止输出 Markdown 或代码块。
                           输出示例：{"items":[{"age":0,"score":60,"content":"...", "open":60,"close":60,"high":65,"low":55,"trend":"Bullish"}]}
                        """,
                bazi.getYearPillar(), bazi.getMonthPillar(), bazi.getDayPillar(), bazi.getHourPillar(), gender,
                bazi.getDaYunList().toString()
        );

        try {
            String raw = chatClient.prompt().user(prompt).call().content();
            log.info("[{}] yearly raw: {}", requestId, abbreviate(raw));
            YearlyBatchResult result = parseWithFastjson(raw, YearlyBatchResult.class);
            return result != null && result.getItems() != null ? result.getItems() : Collections.emptyList();
        } catch (Exception e) {
            System.err.println("AI 流年生成失败: " + e.getMessage());
            log.error("[{}] yearly generation failed: {}", requestId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 融合逻辑：将 AI 文本/分数与 K 线结构合并。
     */
    public List<FateKLinePoint> buildKLine(int birthYear, List<FateResponse.DaYunInfo> daYuns, List<YearlyBatchResult.YearlyItem> aiItems) {
        List<FateKLinePoint> points = new ArrayList<>();
        Map<Integer, YearlyBatchResult.YearlyItem> aiMap = aiItems == null
                ? new HashMap<>()
                : aiItems.stream().collect(Collectors.toMap(YearlyBatchResult.YearlyItem::getAge, item -> item, (k1, k2) -> k1));

        for (int age = 0; age <= 80; age++) {
            int currentYear = birthYear + age;
            String ganZhi = calcService.getYearGanZhi(currentYear);

            String currentDaYun = "童限";
            for (FateResponse.DaYunInfo dy : daYuns) {
                if (age >= dy.getStartAge()) {
                    currentDaYun = dy.getGanZhi();
                }
            }

            YearlyBatchResult.YearlyItem aiItem = aiMap.get(age);
            int score;
            String desc;

            if (aiItem != null) {
                score = aiItem.getScore();
                desc = aiItem.getContent();
            } else {
                score = 50 + (int) (Math.sin(age * 0.2) * 10);
                desc = "流年平稳，守成在此刻。";
            }

            // K线形态统一由后端算法生成，避免 LLM 总是给出单边(全涨/全跌)导致颜色单一或无影线
            int open = points.isEmpty() ? score : points.get(points.size() - 1).getClose();
            int close = score;

            int body = Math.abs(close - open);
            int wick = Math.max(2, body / 3 + 2);
            int high = Math.max(open, close) + wick;
            int low = Math.min(open, close) - wick;

            // 若 LLM 给了 high/low，做边界合并（不直接使用其 open/close）
            if (aiItem != null && aiItem.getHigh() != null) {
                high = Math.max(high, aiItem.getHigh());
            }
            if (aiItem != null && aiItem.getLow() != null) {
                low = Math.min(low, aiItem.getLow());
            }

            // clamp 到 [0, 100]，保证图形尺度稳定
            high = Math.min(100, Math.max(0, high));
            low = Math.min(100, Math.max(0, low));

            // 保证上下影线存在
            if (high <= Math.max(open, close)) {
                high = Math.min(100, Math.max(open, close) + 2);
            }
            if (low >= Math.min(open, close)) {
                low = Math.max(0, Math.min(open, close) - 2);
            }

            String trend = close >= open ? "Bullish" : "Bearish";
            String finalGanZhi = aiItem != null && StringUtils.hasText(aiItem.getGanZhi()) ? aiItem.getGanZhi() : ganZhi;
            String finalDaYun = aiItem != null && StringUtils.hasText(aiItem.getDaYun()) ? aiItem.getDaYun() : currentDaYun;

            FateKLinePoint point = FateKLinePoint.builder()
                    .age(age)
                    .year(currentYear)
                    .ganZhi(finalGanZhi)
                    .daYun(finalDaYun)
                    .score(score)
                    .open(open)
                    .close(close)
                    .high(high)
                    .low(low)
                    .trend(trend)
                    .description(desc)
                    .build();
            points.add(point);
        }
        return points;
    }

    private <T> T parseWithFastjson(String raw, Class<T> clazz) {
        String normalized = normalizeJson(raw);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        try {
            return JSON.parseObject(normalized, clazz, JSON_FEATURES);
        } catch (Exception ex) {
            System.err.println("fastjson2 解析失败 (" + clazz.getSimpleName() + "): " + ex.getMessage());
            return null;
        }
    }

    private String normalizeJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return trimmed;
    }

    private String abbreviate(String raw) {
        if (raw == null) {
            return "";
        }
        String clean = raw.replaceAll("\\s+", " ");
        return clean.length() > 200 ? clean.substring(0, 200) + "..." : clean;
    }

    private String resolveRequestId(FateRequest req) {
        if (req.getRequestId() != null && !req.getRequestId().isBlank()) {
            return req.getRequestId();
        }
        String rid = UUID.randomUUID().toString();
        req.setRequestId(rid);
        return rid;
    }

    private FateAnalysisReport ensureSections(FateAnalysisReport report) {
        FateAnalysisReport safe = report != null ? report : new FateAnalysisReport();
        if (safe.getOverall() == null) {
            safe.setOverall(new FateAnalysisReport.Section());
        }
        if (safe.getInvestment() == null) {
            safe.setInvestment(new FateAnalysisReport.Section());
        }
        if (safe.getCareer() == null) {
            safe.setCareer(new FateAnalysisReport.Section());
        }
        if (safe.getWealth() == null) {
            safe.setWealth(new FateAnalysisReport.Section());
        }
        if (safe.getLove() == null) {
            safe.setLove(new FateAnalysisReport.Section());
        }
        return safe;
    }
}
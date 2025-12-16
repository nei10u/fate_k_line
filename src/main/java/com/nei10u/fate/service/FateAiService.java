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
import org.springframework.beans.factory.annotation.Value;
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
              "love": {"score": 0, "content": "", "summary": ""},
              "health": {"score": 0, "content": "", "summary": ""},
              "family": {"score": 0, "content": "", "summary": ""},
            }
            """;

    private final ChatClient chatClient;
    private final FateCalculationService calcService;

    @Value("${fate.ai.fallback-enabled:true}")
    private boolean fallbackEnabled;

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
                        4. 身体健康简述。
                        5. 六亲关系简述。
                        5. 必须严格返回 JSON，不要包含 Markdown、额外引号或注释。

                        输出格式示例（严格遵守键名与结构）：
                        %s
                        """,
                bazi.getYearPillar(), bazi.getMonthPillar(), bazi.getDayPillar(), bazi.getHourPillar(), gender,
                REPORT_SCHEMA_HINT
        );

        try {
            String raw = chatClient.prompt().user(prompt).call().content();
            log.info("report raw: {}", abbreviate(raw));
            FateAnalysisReport parsed = parseWithFastjson(raw, FateAnalysisReport.class);
            if (parsed == null) {
                String msg = "AI 输出非 JSON 或解析失败（请检查 OpenRouter 配置/模型输出）";
                log.warn("report parse failed, fallbackEnabled={}", fallbackEnabled);
                if (!fallbackEnabled) {
                    throw new IllegalStateException(msg);
                }
                return ensureSections(null, msg);
            }
            return ensureSections(parsed, null);
        } catch (Exception e) {
            String msg = "AI 报告生成失败（请检查 OpenRouter API Key / HTTP-Referer / 模型配额）";
            log.error("{}: {}", msg, e.getMessage(), e);
            if (!fallbackEnabled) {
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
            return ensureSections(null, msg);
        }
    }

    /**
     * 任务 B: 生成 0-80 岁流年详批（fastjson2 解析）
     */
    public List<YearlyBatchResult.YearlyItem> generateYearlyBatch(FateResponse.BaZiInfo bazi, String gender, String requestId) {
        String prompt = String.format("""
                        你是一位研习命理数十年的老先生，深谙「人生运势如行市，有起有伏，但自有其节律」。
                                                
                        请为用户撰写【1-100岁流年详批全表】。
                                                
                        用户八字：%s %s %s %s（性别：%s）
                        大运排盘：%s
                                                
                        【核心任务】
                        生成从 1 岁到 100 岁（共 100 条）的逐年流年运势分析，并以“人生 K 线”的形式量化呈现。
                                                
                        【命理内容要求】
                        1. 每一年必须结合该流年的天干地支，与原局、大运之间的刑、冲、合、害、穿、破进行分析。
                           - 示例：“丙午流年，子午相冲，水火激战，主情绪波动、心血管或财务起伏。”
                           - 示例：“甲申流年，伤官见官，事业是非，名誉受损。”
                                                
                        【人生 K 线规则（非常重要）】
                        2. 每一年需给出 K 线价：
                           - open / close / trend
                           - 数值范围：0–100
                           - open ≠ close
                           - score = |close - open|
                                                
                        3. K 线必须严格遵循“人生成长规律”，而非股票投机逻辑：
                                                
                        【年龄分段约束】
                        - 1–12 岁：运势受家庭与先天影响，年波动 ≤ 5
                        - 13–25 岁：学习与选择期，年波动 ≤ 10
                        - 26–45 岁：人生主升或主跌阶段，允许年波动 ≤ 20
                        - 46–60 岁：修正与守成期，年波动 ≤ 10
                        - 61–100 岁：回归平稳期，年波动 ≤ 5
                                                
                        4. 连贯性规则：
                           - 当年的 open 必须等于上一年的 close
                           - 若该年为“吉年”，则 close > open，trend = Bullish
                           - 若该年为“凶年”，则 close < open，trend = Bearish
                           - 吉年只允许“温和走强”，凶年只允许“理性回落”，禁止极端跳变
                                                
                        5. K 线走势必须与命理评语一致：
                           - 大冲、大刑 → 下行幅度更明显
                           - 合局、喜神 → 上行但不过度
                           - 晚年不允许出现暴涨暴跌
                                                
                        【输出要求】
                        6. 请仅返回 JSON，不要输出任何解释、Markdown 或代码块。
                                                
                        JSON 格式如下：
                        {
                          "items": [
                            {
                              "age": 1,
                              "score": 3,
                              "open": 52,
                              "close": 55,
                              "trend": "Bullish",
                              "content": "..."
                            }
                          ]
                        }
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
            log.error("[{}] AI 流年生成失败: {}", requestId, e.getMessage(), e);
            if (!fallbackEnabled) {
                throw (RuntimeException) e;
            }
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

        for (int age = 1; age <= 100; age++) {
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

    private FateAnalysisReport ensureSections(FateAnalysisReport report, String fallbackMessage) {
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
        if (safe.getHealth() == null) {
            safe.setHealth(new FateAnalysisReport.Section());
        }
        if (safe.getFamily() == null) {
            safe.setFamily(new FateAnalysisReport.Section());
        }
        if (StringUtils.hasText(fallbackMessage)) {
            applyFallbackMessage(safe.getOverall(), fallbackMessage);
            applyFallbackMessage(safe.getInvestment(), fallbackMessage);
            applyFallbackMessage(safe.getCareer(), fallbackMessage);
            applyFallbackMessage(safe.getWealth(), fallbackMessage);
            applyFallbackMessage(safe.getLove(), fallbackMessage);
            applyFallbackMessage(safe.getHealth(), fallbackMessage);
            applyFallbackMessage(safe.getFamily(), fallbackMessage);
        }
        return safe;
    }

    private void applyFallbackMessage(FateAnalysisReport.Section section, String msg) {
        if (section == null) {
            return;
        }
        if (!StringUtils.hasText(section.getSummary())) {
            section.setSummary(msg);
        }
        if (!StringUtils.hasText(section.getContent())) {
            section.setContent(msg);
        }
        // score 保持为默认值（0），用于前端识别“不可用/兜底”
    }
}
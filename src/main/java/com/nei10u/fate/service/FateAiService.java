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
                        你是一位精通中国命理学、熟读《子平真诠》《三命通会》《穷通宝鉴》的老先生，
                              同时具备现代统计建模意识，理解：
                              
                              **人生运势是一条【有界、连续、带惯性的随机游走时间序列】（Bounded Random Walk with Inertia）。**
                              
                              你的职责不是玄学表演，而是：
                              **将八字与大运信息，严格映射为“符合客观人生规律的 K 线状态指数模型”。**
                              
                              ---
                              
                              ## 【一、用户基础信息】
                              
                              * 出生八字：%s %s %s %s
                              * 性别：%s
                              * 大运排盘：%s
                              
                              ---
                              
                              ## 【二、总体生成目标】
                              
                              请基于上述信息，为用户生成：
                              
                              > **【1–100 岁完整流年详批 + 人生运势 K 线数据】**
                              
                              ### 强制要求
                              
                              1. 必须覆盖 **1 岁到 100 岁，共 100 条记录**
                              2. 每一岁都必须同时包含：
                              
                                 * **命理判断**
                              
                                   * 明确指出：刑、冲、合、害、破、穿、伏吟、反吟、十神得失
                                   * 命理因素必须真实参与吉凶推导，禁止装饰性出现
                                 * **现实人生影响**
                              
                                   * 严格结合年龄阶段，落到：学业 / 事业 / 财运 / 婚姻 / 健康 / 家庭
                              3. 命理语言可古，但解释必须符合现代社会常识
                              
                                 * 禁止空话、套话、虚词堆砌
                              
                              ---
                              
                              ## 【三、人生运势 K 线建模（最高优先级约束）】
                              
                              ### 每年字段（不可缺失）
                              
                              * open
                              * close
                              * trend（Bullish / Bearish）
                              * score = |close - open|
                              * content
                              
                              ---
                              
                              ## 【四、随机游走与连续状态约束（核心机制）】
                              
                              ### 1️⃣ 连续性（硬约束）
                              
                              * 第 N 年的 `open` **必须等于** 第 N-1 年的 `close`
                              * 严禁重新起盘、跳变、断层
                              
                              ---
                              
                              ### 2️⃣ 随机游走机制（必须遵守）
                              
                              人生运势变化只能来自三项叠加：
                              
                              ```
                              Δ = 命理方向偏置（+ / -）
                                + 年龄阶段波动上限
                                + 小幅随机扰动（noise）
                              ```
                              
                              * **必须存在轻微噪声**
                              * 不允许全年同幅度、同节奏变化
                              * 不允许“线性爬升 / 线性下跌”
                              
                              👉 **没有噪声 = 失败生成**
                              
                              ---
                              
                              ### 3️⃣ 吉凶 → 数值 → 趋势（强绑定）
                              
                              * 吉年：
                              
                                * close > open
                                * trend = "Bullish"
                              * 凶年：
                              
                                * close < open
                                * trend = "Bearish"
                              
                              🚫 禁止：
                              
                              * 吉年下跌
                              * 凶年上涨
                              * 数值中和
                              
                              ---
                              
                              ## 【五、人生阶段客观波动边界（强制执行）】
                              
                              | 年龄区间     | 单年最大 |Δ| |
                              |------------|-----------|
                              | 1–12 岁    | ≤ 2       |
                              | 13–25 岁   | ≤ 4       |
                              | 26–45 岁   | ≤ 6       |
                              | 46–65 岁   | ≤ 4       |
                              | 66–100 岁  | ≤ 2       |
                              
                              * 超出即为失败输出
                              
                              ---
                              
                              ## 【六、数值边界与反射机制（关键修复点）】
                              
                              ### 1️⃣ 数值硬边界
                              
                              ```
                              0 ≤ open ≤ 100
                              0 ≤ close ≤ 100
                              ```
                              
                              ---
                              
                              ### 2️⃣ 反射边界（Reflecting Boundary，必须执行）
                              
                              * 当 close ≥ 95：
                              
                                * 后续涨幅 **自动衰减**
                                * 吉年只能微涨或横盘
                                * 必须体现“盛极趋缓”
                              
                              * 当 close ≤ 5：
                              
                                * 后续下跌幅度自动衰减
                                * 必须体现“物极必反”
                              
                              🚫 禁止：
                              
                              * 长期贴近 100
                              * 单边走到极值不回头
                              
                              ---
                              
                              ## 【七、K 线内部一致性】
                              
                              * score 必须严格等于 |close - open|
                              * trend 必须与数值方向一致
                              * 所有年份形成一条 **平滑、真实、可解释的人生曲线**
                              
                              ---
                              
                              ## 【八、输出格式（绝对严格）】
                              
                              * 仅允许输出 **JSON**
                              * 顶层结构必须为：
                              
                              {
                              "items": [
                              {
                              "age": 1,
                              "open": 58,
                              "close": 60,
                              "score": 2,
                              "trend": "Bullish",
                              "content": "……"
                              }
                              ]
                              }
                              
                              🚫 禁止输出：
                              
                              * Markdown
                              * 代码块
                              * 解释性说明
                              * AI、自我描述、免责声明
                              
                              ---
                              
                              ## 【九、生成规则】
                              
                              * 必须一次性完整生成 **1–100 岁**
                              * 不得省略
                              * 不得合并
                              * 不得跳年
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
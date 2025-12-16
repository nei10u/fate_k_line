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
import java.util.Random;
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
              "family": {"score": 0, "content": "", "summary": ""}
            }
            """;

    /**
     * 第一段：命格基线（长期均值 μ）输出格式。
     * 仅用于 fastjson2 解析 LLM JSON 输出。
     */
    public static class BaselineResult {
        private Integer baseline;
        private String analysis;

        public Integer getBaseline() {
            return baseline;
        }

        public void setBaseline(Integer baseline) {
            this.baseline = baseline;
        }

        public String getAnalysis() {
            return analysis;
        }

        public void setAnalysis(String analysis) {
            this.analysis = analysis;
        }
    }

    /**
     * 三段式 Prompt - 第一段输出：逐年命理事实表（纯因果层，禁止任何数值/趋势/K线字段）。
     */
    public static class YearlyFactsResult {
        private List<YearlyFactItem> items;

        public List<YearlyFactItem> getItems() {
            return items;
        }

        public void setItems(List<YearlyFactItem> items) {
            this.items = items;
        }
    }

    public static class YearlyFactItem {
        private int age;
        private String dayun;
        private String dayun_effect;
        private String liunian;
        private List<String> relations;
        private String judgement; // 偏吉 / 偏凶 / 中平
        private String comment;   // 命理事实摘要 + 现实落点提示

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public String getDayun() {
            return dayun;
        }

        public void setDayun(String dayun) {
            this.dayun = dayun;
        }

        public String getDayun_effect() {
            return dayun_effect;
        }

        public void setDayun_effect(String dayun_effect) {
            this.dayun_effect = dayun_effect;
        }

        public String getLiunian() {
            return liunian;
        }

        public void setLiunian(String liunian) {
            this.liunian = liunian;
        }

        public List<String> getRelations() {
            return relations;
        }

        public void setRelations(List<String> relations) {
            this.relations = relations;
        }

        public String getJudgement() {
            return judgement;
        }

        public void setJudgement(String judgement) {
            this.judgement = judgement;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }

    /**
     * 三段式 Prompt - 第二段输出：量化规则映射表（规则层，不输出任何年龄序列/K线数据）。
     * 这里用 Map 承载，便于后续扩展规则字段。
     */
    public static class QuantRuleSet {
        private Object direction_rules;
        private Object amplitude_rules;
        private Object inertia_rules;
        private Object boundary_rules;

        public Object getDirection_rules() {
            return direction_rules;
        }

        public void setDirection_rules(Object direction_rules) {
            this.direction_rules = direction_rules;
        }

        public Object getAmplitude_rules() {
            return amplitude_rules;
        }

        public void setAmplitude_rules(Object amplitude_rules) {
            this.amplitude_rules = amplitude_rules;
        }

        public Object getInertia_rules() {
            return inertia_rules;
        }

        public void setInertia_rules(Object inertia_rules) {
            this.inertia_rules = inertia_rules;
        }

        public Object getBoundary_rules() {
            return boundary_rules;
        }

        public void setBoundary_rules(Object boundary_rules) {
            this.boundary_rules = boundary_rules;
        }
    }

    /**
     * 固定量化规则（用户提供的 JSON 规则固化到后端，避免 Prompt② 生成的不确定性）。
     *
     * 说明：
     * - 规则里的 base_amplitude 是比例（0~1），后端会映射到 0~100 指数上的 delta（步长）
     * - liunian_relation_type 通过 facts.relations 归一化提取
     */
    private static final class FixedQuantRules {
        /**
         * K线柱体放大系数：
         * - 用户希望“柱体太短，放大2倍”
         * - 该系数作用于执行层的 delta（步长），并会在后续再被年龄段上限 clamp
         */
        private static final double KLINE_DELTA_SCALE = 2.0;
        private static final Map<String, String> DIRECTION; // key = dayunEffect + "|" + relationType
        private static final Map<String, Double> BASE_AMP;  // key = "0-20"/"21-40"/...
        private static final Map<String, Double> DAYUN_MULT;
        private static final Map<String, Double> REL_MULT;

        private static final int MAX_CONSECUTIVE_GOOD = 3;
        private static final double GOOD_REDUCTION = 0.8;
        private static final int MAX_CONSECUTIVE_BAD = 3;
        private static final double BAD_REDUCTION = 0.7;

        private static final double FUYIN_MULT = 1.7;
        private static final double FUYIN_CONTROL_LIMIT = 0.15;
        private static final double FANYIN_MULT = 1.6;
        private static final double FANYIN_CONTROL_LIMIT = 0.10;

        private static final double HIGH_THRESHOLD = 0.9;
        private static final double HIGH_DULL_FACTOR = 0.5;
        private static final double LOW_THRESHOLD = 0.1;
        private static final double LOW_PULL_FACTOR = 0.6;

        static {
            Map<String, String> dir = new HashMap<>();
            // 扶身
            dir.put("扶身|生", "上涨");
            dir.put("扶身|合", "上涨");
            dir.put("扶身|半合", "小幅波动");
            // 克身
            dir.put("克身|克", "下跌");
            dir.put("克身|冲", "下跌");
            dir.put("克身|害", "下跌");
            // 中性
            dir.put("中性|相冲", "小幅波动");
            dir.put("中性|相害", "小幅波动");
            dir.put("中性|相生", "小幅波动");
            dir.put("中性|无明显关系", "小幅波动");
            DIRECTION = Collections.unmodifiableMap(dir);

            Map<String, Double> base = new HashMap<>();
            base.put("0-20", 0.05);
            base.put("21-40", 0.03);
            base.put("41-60", 0.02);
            base.put("61-80", 0.01);
            base.put("81-100", 0.005);
            BASE_AMP = Collections.unmodifiableMap(base);

            Map<String, Double> dm = new HashMap<>();
            dm.put("扶身", 1.2);
            dm.put("克身", 1.5);
            dm.put("中性", 1.0);
            DAYUN_MULT = Collections.unmodifiableMap(dm);

            Map<String, Double> rm = new HashMap<>();
            rm.put("生", 1.1);
            rm.put("克", 1.3);
            rm.put("合", 1.05);
            rm.put("冲", 1.4);
            rm.put("害", 1.35);
            rm.put("半合", 1.02);
            // 事实层可能产出“相冲/相害/相生/无明显关系”，按 1.0 处理
            REL_MULT = Collections.unmodifiableMap(rm);
        }

        private FixedQuantRules() {
        }
    }

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
        BaselineResult baseline = generateBaseline(bazi, req.getGender(), requestId);
        FateAnalysisReport report = generateReport(bazi, req.getGender());
        log.info("[{}] report generated", requestId);
        List<YearlyBatchResult.YearlyItem> yearlyItems = generateYearlyScoresOneShot(bazi, req.getGender(), baseline.getBaseline(), requestId);
        log.info("[{}] yearly score items size={}", requestId, yearlyItems.size());
        List<FateKLinePoint> kLineData = buildKLineFromYearlyScores(req.getYear(), bazi.getDaYunList(), yearlyItems, baseline.getBaseline());
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
     * 第一段（定盘）：生成命格基线 baseline（长期均值 μ）。
     *
     * 设计目标：
     * - 只做“参数估计”，不生成任何年度、波动、K线内容
     * - baseline 被后续 K 线建模当作均值回归中心（Mean Reversion Center）
     */
    public BaselineResult generateBaseline(FateResponse.BaZiInfo bazi, String gender, String requestId) {
        String prompt = String.format("""
                        你是一位精通中国命理学、熟读《子平真诠》《三命通会》《穷通宝鉴》的老先生，
                        同时具备现代统计建模意识。

                        你理解：
                        人生运势可以抽象为一个“围绕命格基础值上下波动的长期状态指数”。
                        当前任务只做一件事：定命格基线（Baseline）。

                        【一、用户基础信息】
                        出生八字：%s %s %s %s
                        性别：%s
                        大运排盘：%s

                        【二、任务目标（极其重要）】
                        请基于八字结构与大运总体质量，评估此人一生的：
                        【人生运势基础分（Life State Baseline Score）】

                        这是一个 0–100 的长期均值，代表：
                        若无流年扰动
                        若取人生平均状态
                        此人一生运势大致“站在什么水平线附近”

                        【三、命理评估要求（必须真实参与）】
                        你必须综合评估并明确说明：
                        - 日主强弱
                        - 用神 / 忌神是否清晰
                        - 格局高低（普通 / 清 / 真 / 杂）
                        - 大运整体走向（顺 / 逆 / 吉多 / 凶多）
                        - 是否存在明显结构性缺陷（如财多身弱、官杀混杂等）
                              
                              🚫 禁止：
                        - 只给结论不解释
                        - 用空泛吉凶词汇

                        【四、数值约束（硬约束）】
                        输出一个整数 baseline，必须满足：20 ≤ baseline ≤ 80

                        【五、输出格式（绝对严格）】
                        仅允许输出 JSON，格式如下：
                        {
                          "baseline": 62,
                          "analysis": "……"
                              }
                              
                              🚫 禁止输出：
                        Markdown、代码块、年龄、K线、任何年度描述、AI自述或免责声明
                        """,
                bazi.getYearPillar(), bazi.getMonthPillar(), bazi.getDayPillar(), bazi.getHourPillar(),
                gender,
                bazi.getDaYunList().toString()
        );

        try {
            String raw = chatClient.prompt().user(prompt).call().content();
            // baseline 输出仅用于调试，避免日志过长
            log.info("[{}] baseline raw: {}", requestId, abbreviate(raw));
            BaselineResult parsed = parseWithFastjson(raw, BaselineResult.class);
            BaselineResult safe = parsed != null ? parsed : new BaselineResult();
            int base = safe.getBaseline() == null ? 50 : safe.getBaseline();
            // 强制约束：20..80
            base = Math.max(20, Math.min(80, base));
            safe.setBaseline(base);
            if (!StringUtils.hasText(safe.getAnalysis())) {
                safe.setAnalysis("baseline 已生成（内容为空，可能是模型输出缺失）。");
            }
            return safe;
        } catch (Exception e) {
            log.error("[{}] baseline 生成失败: {}", requestId, e.getMessage(), e);
            if (!fallbackEnabled) {
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            }
            BaselineResult fallback = new BaselineResult();
            fallback.setBaseline(50);
            fallback.setAnalysis("baseline 生成失败，已使用默认值 50。");
            return fallback;
        }
    }

    /**
     * 单次生成（回到“最初一次生成”的方案）：
     * - LLM 只输出 1-100 岁每年的“绝对分数 score（1-100）+批注 content”
     * - K 线的 open/close/trend 由后端严格按规则派生：
     *   - 第 1 年 open = baseline
     *   - 第 N 年 open = 第 N-1 年 close
     *   - 第 N 年 close = 当年 score
     *   - close > open => Bullish（绿）；否则 Bearish（红）
     *
     * 这样可以避免让模型同时维护长序列一致性（连续性/颜色/边界），把一致性交给后端。
     */
    public List<YearlyBatchResult.YearlyItem> generateYearlyScoresOneShot(FateResponse.BaZiInfo bazi,
                                                                         String gender,
                                                                         int baseline,
                                                                         String requestId) {
        int safeBaseline = Math.max(20, Math.min(80, baseline));
        String prompt = String.format("""
                        你是一位精通“八字命理”与“金融数据分析”的专家。请基于我提供的八字信息，模拟生成一份长达 80 年的“人生运势 K 线数据”。
                                                
                        # Input Data (八字)
                        - 年柱：%s
                        - 月柱：%s
                        - 日柱：%s
                        - 时柱：%s
                        - 大运方向：逆行（1岁起运）
                        - 大运序列参考：
                          %s
                                                
                        # Algorithms (评分逻辑)
                        1. **基础分 (Base):** 初始分设为 %s。
                        2. **大运分 (Trend):** 根据上述大运序列设定底分区间。例如“癸酉/壬申”运底分在 80-90，“甲戌/庚午”运底分在 40-50。
                        3. **流年波动 (Volatility):**
                           - 遇到“金/水”流年（如申、酉、亥、子、庚、辛、壬、癸），当年分数显著上涨。
                           - 遇到“火/土”流年（如巳、午、未、戌、丙、丁、戊、己），当年分数下跌或调整。
                        4. **K线连续性规则 (核心):**
                           - 第 N 年的 `open` 必须严格等于第 N-1 年的 `close`。
                           - `close` 由当年的运势打分决定。
                           - `score` 字段直接取当年的 `close` 值。
                        5. 一年一条数据，预测80年，一共80条数据。
                        6. **content 必须包含命理依据 + 现实影响（结合年龄阶段）**
                        
                        # Output Format (严格 JSON)
                        请仅输出一个 JSON 对象，包含一个 "items" 数组。不要包含任何 Markdown 代码块标记（如 ```json），也不要包含任何解释性文字。
                                                
                        JSON 结构示例：
                        {
                          "items": [
                            {"age": 1, "open": 50, "close": 55, "content": "..."},
                            {"age": 2, "open": 55, "close": 52,"content": "..."},
                            // ... 直到 age 80
                          ]
                        }
                        请开始生成JSON数据
                        """,
                bazi.getYearPillar(), bazi.getMonthPillar(), bazi.getDayPillar(), bazi.getHourPillar(),
                bazi.getDaYunList().toString(),
                safeBaseline
        );

        try {
            String raw = chatClient.prompt().user(prompt).call().content();
            log.info("[{}] yearly-score raw: {}", requestId, raw);
            YearlyBatchResult result = parseWithFastjson(raw, YearlyBatchResult.class);
            if (result == null || result.getItems() == null) {
                return Collections.emptyList();
            }
            // 兼容模型输出仅包含 open/close/content（未显式输出 score）的情况：
            // - 后端的 K 线构建依赖“年度绝对分数”，此时可将 close 视为年度分数。
            for (YearlyBatchResult.YearlyItem it : result.getItems()) {
                if (it == null) {
                    continue;
                }
                if (it.getScore() <= 0 && it.getClose() != null) {
                    it.setScore(it.getClose());
                }
            }
            return result.getItems();
        } catch (Exception e) {
            log.error("[{}] yearly-score 生成失败: {}", requestId, e.getMessage(), e);
            if (!fallbackEnabled) {
                throw (RuntimeException) e;
            }
            return Collections.emptyList();
        }
    }

    /**
     * 将“年度绝对分数序列”映射为 K 线点位（后端保证连续性与颜色判定一致性）。
     */
    public List<FateKLinePoint> buildKLineFromYearlyScores(int birthYear,
                                                          List<FateResponse.DaYunInfo> daYuns,
                                                          List<YearlyBatchResult.YearlyItem> aiItems,
                                                          int baseline) {
        Map<Integer, YearlyBatchResult.YearlyItem> aiMap = aiItems == null
                ? new HashMap<>()
                : aiItems.stream().collect(Collectors.toMap(YearlyBatchResult.YearlyItem::getAge, it -> it, (a, b) -> a));

        int prevClose = Math.max(20, Math.min(80, baseline));
        List<FateKLinePoint> points = new ArrayList<>(80);

        for (int age = 1; age <= 80; age++) {
            int currentYear = birthYear + (age - 1);
            String ganZhi = calcService.getYearGanZhi(currentYear);

            String currentDaYun = "童限";
            for (FateResponse.DaYunInfo dy : daYuns) {
                if (age >= dy.getStartAge()) {
                    currentDaYun = dy.getGanZhi();
                }
            }

            YearlyBatchResult.YearlyItem ai = aiMap.get(age);
            // 兼容模型输出：
            // - 若输出了 close（绝对分数），优先使用 close
            // - 否则退化为 score
            Integer modelClose = ai != null ? ai.getClose() : null;
            int closeScore = modelClose != null ? modelClose : (ai != null ? ai.getScore() : prevClose);
            closeScore = Math.max(1, Math.min(100, closeScore));

            int open = prevClose;
            int close = closeScore;
            String trend = close > open ? "Bullish" : "Bearish";
            int score = Math.abs(close - open);

            String desc = ai != null && StringUtils.hasText(ai.getContent()) ? ai.getContent() : "当年运势已生成。";
            String finalGanZhi = ai != null && StringUtils.hasText(ai.getGanZhi()) ? ai.getGanZhi() : ganZhi;
            String finalDaYun = ai != null && StringUtils.hasText(ai.getDaYun()) ? ai.getDaYun() : currentDaYun;

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
            prevClose = close;
        }
        return points;
    }

    /**
     * 三段式 Prompt：年度事实表 -> 量化规则 -> 规则驱动 K 线。
     * <p>
     * baseline：
     * - 仍然保留（由 step1 定盘得到），但在三段式中不强制要求模型使用它做数值回归；
     * - 后端 normalizeKlineItems(...) 会以 baseline 作为均值回归中心做最终兜底（产品级一致性）。
     */
    public List<YearlyBatchResult.YearlyItem> generateKlineItemsThreeStage(FateResponse.BaZiInfo bazi,
                                                                          String gender,
                                                                          int baseline,
                                                                          String requestId) {
        YearlyFactsResult facts = generateYearlyFacts(bazi, gender, requestId);
        // 第二步“量化规则层”固定：不再调用 LLM（可复现、可调参）
        // 第三步“执行层”由后端代码执行（避免模型输出 open/close 长序列失控）
        return executeKlineFromFactsWithFixedRules(facts, baseline);
    }

    /**
     * Prompt①：八字 -> 逐年大运事实表（禁止任何数值/K线字段）
     */
    public YearlyFactsResult generateYearlyFacts(FateResponse.BaZiInfo bazi, String gender, String requestId) {
        String prompt = String.format("""
                        你是一位精通中国命理学、熟读《子平真诠》《三命通会》《穷通宝鉴》的老先生。

                        你当前只允许做命理事实推演，不允许做任何数值建模或运势量化。

                        【一、用户基础信息】
                        出生八字：%s %s %s %s
                        性别：%s
                        大运排盘：%s

                        【二、任务目标】
                        请基于八字与大运排盘，生成：
                        【1–100 岁逐年大运与流年命理事实表】
                        这是一个纯命理层的“年度事实清单”，用于后续量化，不是最终结果。

                        【三、每一年必须包含（不可缺失）】
                        对每一个年龄（1–100 岁），必须给出：
                        - 当年所处大运（dayun）
                        - 大运干支（dayun）
                        - 运势性质（dayun_effect：扶身 / 克身 / 中性）
                        - 流年作用（liunian）
                        - 流年干支（liunian）
                        - 与原局 / 大运的关系（relations：刑/冲/合/害/破/穿/伏吟/反吟/十神得失等）
                        - 综合命理判断（judgement：偏吉 / 偏凶 / 中平 三选一）
                        - 现实落点提示（comment：结合年龄阶段落到学业/事业/财运/婚姻/健康/家庭，必须具体）

                        【四、严格禁止】
                        🚫 禁止出现：
                        - 任何数值（分数、区间、涨跌）
                        - K线、走势、指数、趋势词
                        - open / close / Bullish / Bearish
                        - “整体来看”“大体不错”等模糊表述

                        【五、输出格式（绝对严格）】
                        仅允许输出 JSON，格式如下：
                              {
                              "items": [
                              {
                              "age": 1,
                              "dayun": "甲子",
                              "dayun_effect": "扶身",
                              "liunian": "乙丑",
                              "relations": ["合", "十神得力"],
                              "judgement": "偏吉",
                              "comment": "童年阶段家庭助力较强，体质平稳，学业启蒙顺利"
                            }
                          ]
                        }

                        🚫 禁止输出：Markdown、代码块、解释性说明、AI自述
                        """,
                bazi.getYearPillar(), bazi.getMonthPillar(), bazi.getDayPillar(), bazi.getHourPillar(),
                gender,
                bazi.getDaYunList().toString()
        );

        try {
            String raw = chatClient.prompt().user(prompt).call().content();
            log.info("[{}] facts raw: {}", requestId, abbreviate(raw));
            YearlyFactsResult parsed = parseWithFastjson(raw, YearlyFactsResult.class);
            return parsed != null ? parsed : new YearlyFactsResult();
        } catch (Exception e) {
            log.error("[{}] facts 生成失败: {}", requestId, e.getMessage(), e);
            if (!fallbackEnabled) {
                throw (RuntimeException) e;
            }
            return new YearlyFactsResult();
        }
    }

    /**
     * 三段式第三步执行层（后端执行，不再调用 LLM）：
     * - 输入：facts（纯命理事实表）+ baseline
     * - 输出：带 open/close/score/trend/content 的 items
     *
     * 解释：
     * - “量化规则”已固定到代码（FixedQuantRules），因此执行完全可复现
     * - 生成出的 items 仍会被 normalizeKlineItems(...) 再做产品级兜底
     */
    private List<YearlyBatchResult.YearlyItem> executeKlineFromFactsWithFixedRules(YearlyFactsResult facts, int baseline) {
        Map<Integer, YearlyFactItem> factMap = new HashMap<>();
        if (facts != null && facts.getItems() != null) {
            for (YearlyFactItem it : facts.getItems()) {
                factMap.put(it.getAge(), it);
            }
        }

        int safeBaseline = Math.max(20, Math.min(80, baseline));
        int open = safeBaseline;
        int consecutiveBull = 0;
        int consecutiveBear = 0;
        Random rnd = new Random(42); // 固定种子：可复现（也可改为 requestId hash）

        List<YearlyBatchResult.YearlyItem> items = new ArrayList<>(100);
        for (int age = 1; age <= 100; age++) {
            YearlyFactItem fact = factMap.get(age);

            String daYunEffect = fact != null && StringUtils.hasText(fact.getDayun_effect()) ? fact.getDayun_effect().trim() : "中性";
            String relationType = normalizeRelationType(fact);
            String direction = FixedQuantRules.DIRECTION.getOrDefault(daYunEffect + "|" + relationType, "小幅波动");

            boolean bullish;
            if ("上涨".equals(direction)) {
                bullish = true;
            } else if ("下跌".equals(direction)) {
                bullish = false;
            } else {
                String j = fact != null ? fact.getJudgement() : null;
                if ("偏吉".equals(j)) bullish = true;
                else if ("偏凶".equals(j)) bullish = false;
                else bullish = open <= safeBaseline; // 中平：向 baseline 回归
            }

            double baseAmp = FixedQuantRules.BASE_AMP.getOrDefault(ageBucket(age), 0.02);
            double daYunMult = FixedQuantRules.DAYUN_MULT.getOrDefault(daYunEffect, 1.0);
            double relMult = FixedQuantRules.REL_MULT.getOrDefault(relationType, 1.0);

            double rawDelta = 100.0 * baseAmp * daYunMult * relMult * FixedQuantRules.KLINE_DELTA_SCALE;
            rawDelta *= (0.85 + rnd.nextDouble() * 0.30); // 轻噪声 0.85~1.15
            int delta = Math.max(1, (int) Math.round(rawDelta));

            int maxDelta = maxDeltaByAge(age);
            delta = Math.min(delta, maxDelta);

            // 惯性：连续吉/凶超过阈值后衰减
            if (bullish) {
                consecutiveBull++;
                consecutiveBear = 0;
                if (consecutiveBull > FixedQuantRules.MAX_CONSECUTIVE_GOOD) {
                    delta = Math.max(1, (int) Math.round(delta * Math.pow(FixedQuantRules.GOOD_REDUCTION, consecutiveBull - FixedQuantRules.MAX_CONSECUTIVE_GOOD)));
                }
            } else {
                consecutiveBear++;
                consecutiveBull = 0;
                if (consecutiveBear > FixedQuantRules.MAX_CONSECUTIVE_BAD) {
                    delta = Math.max(1, (int) Math.round(delta * Math.pow(FixedQuantRules.BAD_REDUCTION, consecutiveBear - FixedQuantRules.MAX_CONSECUTIVE_BAD)));
                }
            }

            // 伏吟/反吟：放大但受控
            if (hasKeyword(fact, "伏吟")) {
                delta = (int) Math.round(delta * FixedQuantRules.FUYIN_MULT);
                delta = Math.min(delta, Math.max(1, (int) Math.round(100 * FixedQuantRules.FUYIN_CONTROL_LIMIT)));
            }
            if (hasKeyword(fact, "反吟")) {
                delta = (int) Math.round(delta * FixedQuantRules.FANYIN_MULT);
                delta = Math.min(delta, Math.max(1, (int) Math.round(100 * FixedQuantRules.FANYIN_CONTROL_LIMIT)));
            }

            // 边界保护：高位钝化、低位止跌
            double p = open / 100.0;
            if (bullish && p >= FixedQuantRules.HIGH_THRESHOLD) {
                delta = Math.max(1, (int) Math.round(delta * FixedQuantRules.HIGH_DULL_FACTOR));
            }
            if (!bullish && p <= FixedQuantRules.LOW_THRESHOLD) {
                delta = Math.max(1, (int) Math.round(delta * FixedQuantRules.LOW_PULL_FACTOR));
            }

            // baseline 均值回归：越偏离 baseline，延续同方向越收敛
            int drift = open - safeBaseline;
            if (bullish && drift > 10) {
                delta = Math.max(1, (int) Math.round(delta * 0.7));
            }
            if (!bullish && drift < -10) {
                delta = Math.max(1, (int) Math.round(delta * 0.7));
            }

            int close = bullish ? open + delta : open - delta;
            close = Math.max(0, Math.min(100, close));
            if (bullish && close <= open) close = Math.min(100, open + 1);
            if (!bullish && close >= open) close = Math.max(0, open - 1);

            YearlyBatchResult.YearlyItem out = new YearlyBatchResult.YearlyItem();
            out.setAge(age);
            out.setOpen(open);
            out.setClose(close);
            out.setScore(Math.abs(close - open));
            out.setTrend(bullish ? "Bullish" : "Bearish");
            out.setContent(fact != null && StringUtils.hasText(fact.getComment()) ? fact.getComment() : (bullish ? "偏吉" : "偏凶"));
            if (fact != null) {
                out.setDaYun(fact.getDayun());
                out.setGanZhi(fact.getLiunian());
            }

            items.add(out);
            open = close;
        }
        return items;
    }

    private String ageBucket(int age) {
        if (age <= 20) return "0-20";
        if (age <= 40) return "21-40";
        if (age <= 60) return "41-60";
        if (age <= 80) return "61-80";
        return "81-100";
    }

    private boolean hasKeyword(YearlyFactItem fact, String keyword) {
        if (fact == null) return false;
        if (fact.getRelations() != null) {
            for (String r : fact.getRelations()) {
                if (r != null && r.contains(keyword)) return true;
            }
        }
        return fact.getComment() != null && fact.getComment().contains(keyword);
    }

    private String normalizeRelationType(YearlyFactItem fact) {
        if (fact == null) return "无明显关系";
        List<String> rels = fact.getRelations();
        if (rels == null || rels.isEmpty()) return "无明显关系";

        for (String r : rels) {
            if (r == null) continue;
            if (r.contains("相冲")) return "相冲";
            if (r.contains("相害")) return "相害";
            if (r.contains("相生")) return "相生";
        }
        for (String r : rels) {
            if (r == null) continue;
            if (r.contains("冲")) return "冲";
            if (r.contains("害")) return "害";
            if (r.contains("克")) return "克";
            if (r.contains("半合")) return "半合";
            if (r.contains("合")) return "合";
            if (r.contains("生")) return "生";
        }
        return "无明显关系";
    }

    /**
     * 二段式 K 线后处理与组装（产品级兜底）：
     * <p>
     * 输入：
     * - aiItems：LLM 输出的 1-100 岁条目（包含 trend/content，open/close/score 可能不可靠）
     * - baseline：第一段“定盘”得到的长期均值 μ
     * <p>
     * 输出（保证满足）：
     * - 连贯：open_n = close_{n-1}
     * - 趋势一致：Bullish => close > open；Bearish => close < open
     * - 分数一致：score = |close-open|
     * - 波动上限：按年龄段限制单年最大 |Δ|
     * - 有界：open/close 限制在 [0,100] 且避免长期贴边
     * - 均值回归：越偏离 baseline，延续同方向的幅度越小
     */
    public List<FateKLinePoint> buildKLineWithBaseline(int birthYear,
                                                      List<FateResponse.DaYunInfo> daYuns,
                                                      List<YearlyBatchResult.YearlyItem> aiItems,
                                                      int baseline) {
        List<YearlyBatchResult.YearlyItem> normalizedItems = normalizeKlineItems(aiItems, baseline);
        List<FateKLinePoint> points = new ArrayList<>(normalizedItems.size());

        for (YearlyBatchResult.YearlyItem item : normalizedItems) {
            int age = item.getAge();
            int currentYear = birthYear + (age - 1);
            String ganZhi = calcService.getYearGanZhi(currentYear);

            String currentDaYun = "童限";
            for (FateResponse.DaYunInfo dy : daYuns) {
                if (age >= dy.getStartAge()) {
                    currentDaYun = dy.getGanZhi();
                }
            }

            String finalGanZhi = StringUtils.hasText(item.getGanZhi()) ? item.getGanZhi() : ganZhi;
            String finalDaYun = StringUtils.hasText(item.getDaYun()) ? item.getDaYun() : currentDaYun;

            FateKLinePoint point = FateKLinePoint.builder()
                    .age(age)
                    .year(currentYear)
                    .ganZhi(finalGanZhi)
                    .daYun(finalDaYun)
                    .score(item.getScore())
                    .open(item.getOpen() == null ? 0 : item.getOpen())
                    .close(item.getClose() == null ? 0 : item.getClose())
                    .trend(item.getTrend())
                    .description(item.getContent())
                    .build();
            points.add(point);
        }
        return points;
    }

    private List<YearlyBatchResult.YearlyItem> normalizeKlineItems(List<YearlyBatchResult.YearlyItem> aiItems, int baseline) {
        // 允许 LLM 输出不完整：这里做“最小更正”，确保 K 线模型永远可用。
        Map<Integer, YearlyBatchResult.YearlyItem> aiMap = aiItems == null
                ? new HashMap<>()
                : aiItems.stream().collect(Collectors.toMap(YearlyBatchResult.YearlyItem::getAge, item -> item, (k1, k2) -> k1));

        int safeBaseline = Math.max(20, Math.min(80, baseline));
        int prevClose = safeBaseline;

        List<YearlyBatchResult.YearlyItem> out = new ArrayList<>(100);
        for (int age = 1; age <= 100; age++) {
            YearlyBatchResult.YearlyItem raw = aiMap.get(age);
            YearlyBatchResult.YearlyItem item = raw != null ? raw : new YearlyBatchResult.YearlyItem();
            item.setAge(age);

            int open = prevClose;
            int maxDelta = maxDeltaByAge(age);

            boolean bullish = resolveBullish(item, age, aiMap);
            int direction = bullish ? 1 : -1;

            // 期望步长：优先使用 LLM score，其次使用 LLM close/open 的差值，再兜底给一个带噪声的小步长
            int desiredDelta = item.getScore();
            if (desiredDelta <= 0) {
                Integer ro = item.getOpen();
                Integer rc = item.getClose();
                if (ro != null && rc != null) {
                    desiredDelta = Math.abs(rc - ro);
                }
            }
            if (desiredDelta <= 0) {
                desiredDelta = 1 + (age % Math.max(1, maxDelta)); // 轻噪声（确定性），避免全同幅度
            }

            // 年龄段上限约束
            // 柱体放大 2 倍（与固定规则执行层保持一致）
            int scaledDelta = Math.max(1, desiredDelta * 2);
            int delta = Math.min(scaledDelta, maxDelta);

            // 均值回归：越偏离 baseline，延续偏离方向的幅度越小
            int drift = open - safeBaseline;
            if (direction > 0 && drift > 12) {
                delta = Math.max(1, delta / 2);
            } else if (direction < 0 && drift < -12) {
                delta = Math.max(1, delta / 2);
            }

            // 边界保护：尽量避免贴边运行（使用软边界 5..95）
            int softMin = 5;
            int softMax = 95;
            if (direction > 0 && open >= softMax) {
                delta = 1; // 盛极趋缓
            } else if (direction < 0 && open <= softMin) {
                delta = 1; // 物极必反（但方向不翻转，只缩步）
            }

            int close = open + direction * delta;
            close = Math.max(0, Math.min(100, close));

            // 如果 clamp 导致方向不满足（极端情况下），做最小修正：再收缩一步
            if (bullish && close <= open) {
                close = Math.min(100, open + 1);
            }
            if (!bullish && close >= open) {
                close = Math.max(0, open - 1);
            }

            // 写回：连贯、score、trend
            item.setOpen(open);
            item.setClose(close);
            item.setScore(Math.abs(close - open));
            item.setTrend(bullish ? "Bullish" : "Bearish");
            if (!StringUtils.hasText(item.getContent())) {
                item.setContent(bullish ? "该年运势偏吉，宜顺势而为。" : "该年运势偏凶，宜守不宜攻。");
            }

            out.add(item);
            prevClose = close;
        }
        return out;
    }

    private boolean resolveBullish(YearlyBatchResult.YearlyItem item, int age, Map<Integer, YearlyBatchResult.YearlyItem> aiMap) {
        if (item != null && StringUtils.hasText(item.getTrend())) {
            String t = item.getTrend().trim().toLowerCase();
            if (t.contains("bull")) return true;
            if (t.contains("bear")) return false;
        }
        // 兜底：与上一年 score 比较（更贴近“吉一定更高/凶一定更低”的产品语义）
        if (age > 1) {
            YearlyBatchResult.YearlyItem prev = aiMap.get(age - 1);
            if (prev != null) {
                int currScore = item != null ? item.getScore() : 0;
                int prevScore = prev.getScore();
                if (currScore > 0 && prevScore > 0) {
                    return currScore >= prevScore;
                }
            }
        }
        // 最终兜底：bullish
        return true;
    }

    private int maxDeltaByAge(int age) {
        // 固定量化规则对应的幅度上限（更宽松，匹配你提供的规则配置）
        if (age <= 12) return 4;
        if (age <= 25) return 8;
        if (age <= 45) return 12;
        if (age <= 65) return 8;
        return 4;
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
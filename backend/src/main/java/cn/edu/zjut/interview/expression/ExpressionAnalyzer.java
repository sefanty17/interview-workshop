package cn.edu.zjut.interview.expression;

import cn.edu.zjut.interview.dto.ExpressionReport;
import cn.edu.zjut.interview.dto.ExpressionReport.Metric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表达分析的核心算法（<b>本项目扩展（课设指标 ④）· 文本版</b>）。
 *
 * <p>刻意做成<b>无状态静态工具</b>：输入是已经清洗好的作答列表，输出是可直接给前端的
 * {@link ExpressionReport}。不碰数据库、不调大模型——同一段对话任何时候算出的分数都一样，
 * 报告页刷新不会给出两个不同的结论。
 *
 * <p><b>四个指标的口径</b>（正文里每条规则都写死在代码中，方便答辩时逐条解释）：
 * <ol>
 *   <li><b>口头禅控制</b>：填充词出现次数 / 总字数（每百字），按密度扣分；</li>
 *   <li><b>句子组织</b>：平均句长与句长变异系数（CV），太碎扣分、过于单一扣分、忽长忽短也扣分；</li>
 *   <li><b>作答节奏</b>：从上一句台词到这次作答的时间差（含读题与输入），平均越接近「30 秒内」越好；</li>
 *   <li><b>表达充分度</b>：平均回答字数，太短说明信息量不够。</li>
 * </ol>
 *
 * <p><b>为什么不做「自信度」</b>：文本里没有语气、停顿、音量，从「我觉得 / 可能 / 应该」
 * 这些词反推自信度只是猜测，会把「严谨表述」误判成「不自信」。这类指标必须等语音线。
 */
public final class ExpressionAnalyzer {

    /**
     * 一条作答。
     *
     * @param text            清洗后的原话（已去掉 {@code {情绪: ...}} 信封与动作描写）
     * @param responseDelayMs 相对上一句台词的时间差；{@code null} = 没有可用时间戳
     */
    public record Utterance(String text, Long responseDelayMs) {
    }

    /**
     * 口头禅词表：<b>只收没有信息量的填充词</b>。
     *
     * <p>故意不收「我觉得 / 其实 / 应该」：它们是带判断的正常表述，算成口头禅会把
     * 严谨的表达判低分。按长度从长到短匹配，避免「就是说」被拆成「就是」+「说」重复计数。
     */
    private static final List<String> FILLERS = List.of(
            "怎么说呢", "也就是说", "就是说", "然后呢", "那个", "然后", "反正",
            "基本上", "大概", "差不多", "其实吧", "嗯", "呃", "啊", "emm");

    /** 单次作答间隔超过 10 分钟视为挂机（人跑去干别的了），不计入「思考时长」。 */
    private static final long MAX_IDLE_DELAY_MS = 10L * 60L * 1000L;

    /** 句子分隔符：中文句末标点 + 分号 + 换行。 */
    private static final String SENTENCE_SPLIT_REGEX = "[。！？!?；;\\n]+";

    /** 统计字数时剔除的字符：标点与空白（它们不构成「说了多少内容」）。 */
    private static final String PUNCTUATION_REGEX = "[\\s，。！？、；：\"'“”‘’（）《》【】,.!?;:()\\[\\]{}]";

    private ExpressionAnalyzer() {
    }

    /**
     * 计算一场面试的表达分析。
     *
     * @param sessionId  场次 id（回给前端对账用）
     * @param utterances 已清洗的作答，顺序按 seq 升序；为空时返回一份「无可分析数据」的结果
     */
    public static ExpressionReport analyze(Long sessionId, List<Utterance> utterances) {
        List<Metric> metrics = new ArrayList<>(4);
        List<String> suggestions = new ArrayList<>(3);

        // ---------- 1) 口头禅 ----------
        Map<String, Integer> fillerCounts = new LinkedHashMap<>();
        int totalChars = 0;
        int fillerTotal = 0;
        for (Utterance utterance : utterances) {
            String text = utterance.text() == null ? "" : utterance.text();
            totalChars += countChars(text);
            fillerTotal += countFillers(text, fillerCounts);
        }
        double fillerDensity = totalChars == 0 ? 0.0 : (double) fillerTotal * 100.0 / totalChars;
        int fillerScore = clamp(100 - Math.min(70, (int) Math.round(fillerDensity * 7)), 0, 100);
        metrics.add(new Metric("filler", "口头禅控制", fillerScore,
                String.format("%.1f 次/百字", fillerDensity),
                fillerTotal == 0
                        ? "没有出现明显的填充词，表达干净"
                        : "共出现 " + fillerTotal + " 次填充词（嗯/那个/然后 等），密度越低越好"));
        if (fillerDensity >= 4.0) {
            suggestions.add("口头禅平均每百字 " + String.format("%.1f", fillerDensity)
                    + " 次：作答前先在心里把第一句想好，能明显减少「嗯、那个、然后」");
        }

        // ---------- 2) 句子组织 ----------
        List<Integer> sentenceLengths = new ArrayList<>();
        for (Utterance utterance : utterances) {
            if (utterance.text() == null) {
                continue;
            }
            for (String sentence : utterance.text().split(SENTENCE_SPLIT_REGEX)) {
                int length = countChars(sentence);
                if (length > 0) {
                    sentenceLengths.add(length);
                }
            }
        }
        double avgSentence = mean(sentenceLengths);
        double sentenceCv = coefficientOfVariation(sentenceLengths);
        int sentenceScore = sentenceScore(avgSentence, sentenceCv);
        metrics.add(new Metric("sentence", "句子组织", sentenceScore,
                String.format("平均 %.1f 字/句，波动 %.2f", avgSentence, sentenceCv),
                "句长有长短变化、平均 10 字以上更自然；波动过小接近背书，过大显得跳跃"));
        if (sentenceCv > 0.0 && sentenceCv < 0.20) {
            suggestions.add("句子长度几乎一致（波动 " + String.format("%.2f", sentenceCv)
                    + "）：适当用短句下结论、长句展开，听起来更有层次");
        } else if (sentenceCv > 0.60) {
            suggestions.add("句子长短差异偏大（波动 " + String.format("%.2f", sentenceCv)
                    + "）：先把结论说完，再补充细节，避免一句话里塞太多信息");
        }

        // ---------- 3) 作答节奏 ----------
        List<Long> delays = new ArrayList<>();
        for (Utterance utterance : utterances) {
            Long delay = utterance.responseDelayMs();
            if (delay != null && delay >= 0 && delay <= MAX_IDLE_DELAY_MS) {
                delays.add(delay);
            }
        }
        Integer paceScore = null;
        String paceValue;
        if (delays.isEmpty()) {
            paceValue = "无时间戳";
            metrics.add(new Metric("pace", "作答节奏", null, paceValue,
                    "本场没有可用的作答时间戳，无法计算反应时长"));
        } else {
            double avgDelaySec = mean(delays) / 1000.0;
            paceScore = paceScore(avgDelaySec);
            paceValue = String.format("平均 %.1f 秒", avgDelaySec);
            metrics.add(new Metric("pace", "作答节奏", paceScore, paceValue,
                    "从面试官说完到开始作答的平均时间（含读题与输入）；30 秒内比较从容"));
            if (avgDelaySec > 90) {
                suggestions.add("平均用时 " + String.format("%.0f", avgDelaySec)
                        + " 秒才作答：先给一句结论再展开，比长时间沉默更容易被记住");
            }
        }

        // ---------- 4) 表达充分度 ----------
        double avgChars = utterances.isEmpty() ? 0.0 : (double) totalChars / utterances.size();
        int richnessScore = richnessScore(avgChars);
        metrics.add(new Metric("richness", "表达充分度", richnessScore,
                String.format("平均 %.0f 字/次", avgChars),
                "每次回答 50 字以上才能兼顾结论、依据与例子"));
        if (avgChars < 30) {
            suggestions.add("回答平均只有 " + String.format("%.0f", avgChars)
                    + " 字，信息量偏少：按「结论 + 依据 + 例子」展开能把内容撑起来");
        }

        int overall = weightedOverall(metrics);
        if (suggestions.isEmpty()) {
            suggestions.add("整体表达干净、节奏稳定，继续保持这个作答习惯");
        }

        return new ExpressionReport(
                sessionId,
                utterances.size(),
                overall,
                "text",
                List.copyOf(metrics),
                topFillers(fillerCounts),
                List.copyOf(suggestions.subList(0, Math.min(3, suggestions.size()))),
                "本场分析基于文字输入：口头禅与句长取自你的原话，作答节奏按「上一句台词 → 你这句发言」"
                        + "的时间差估算，包含读题与输入的时间。语速、停顿、清晰度与自信度需要语音识别（#8）"
                        + "落地后补充。");
    }

    /**
     * 口头禅计数：按 {@link #FILLERS} 从长到短做非重叠替换计数。
     *
     * <p>用「长词先占位」而不是简单 {@code indexOf} 循环：否则「就是说」会被同时记成
     * 「就是说」和「然后」之外的词，密度凭空翻倍。
     */
    private static int countFillers(String text, Map<String, Integer> counts) {
        int total = 0;
        String remaining = text;
        for (String filler : FILLERS) {
            int hits = 0;
            while (remaining.contains(filler)) {
                remaining = remaining.replaceFirst(java.util.regex.Pattern.quote(filler), " ");
                hits++;
            }
            if (hits > 0) {
                counts.merge(filler, hits, Integer::sum);
                total += hits;
            }
        }
        return total;
    }

    private static List<String> topFillers(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(entry -> entry.getKey() + "×" + entry.getValue())
                .toList();
    }

    /** 有效字数：去掉标点与空白后的字符数（中文按字算，英文单词按字母算，足够反映信息量）。 */
    private static int countChars(String text) {
        if (text == null) {
            return 0;
        }
        return text.replaceAll(PUNCTUATION_REGEX, "").length();
    }

    private static double mean(List<? extends Number> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Number value : values) {
            sum += value.doubleValue();
        }
        return sum / values.size();
    }

    /** 变异系数 = 标准差 / 平均值（单位为 1，不是百分数）；样本少于 2 时没有「波动」可言，返回 0。 */
    private static double coefficientOfVariation(List<Integer> values) {
        if (values.size() < 2) {
            return 0.0;
        }
        double avg = mean(values);
        if (avg <= 0) {
            return 0.0;
        }
        double sumSquared = 0;
        for (int value : values) {
            double diff = value - avg;
            sumSquared += diff * diff;
        }
        return Math.sqrt(sumSquared / values.size()) / avg;
    }

    /** 句子组织：平均句长 < 10 字按每少 1 字扣 5 分；波动过小扣 10，过大扣 15。 */
    private static int sentenceScore(double avgSentence, double cv) {
        int deductions = 0;
        if (avgSentence > 0 && avgSentence < 10) {
            deductions += Math.min(35, (int) Math.round((10 - avgSentence) * 5));
        }
        // cv == 0 有两种情况：只有一句（无法判断）或句长完全一致。只有后者该扣分
        if (cv > 0 && cv < 0.20) {
            deductions += 10;
        } else if (cv > 0.60) {
            deductions += 15;
        }
        return clamp(100 - deductions, 40, 100);
    }

    /** 作答节奏：30 秒内 100 分，随后每档递减；再慢说明「卡住了」而不是「在思考」。 */
    private static int paceScore(double avgDelaySec) {
        if (avgDelaySec <= 30) {
            return 100;
        }
        if (avgDelaySec <= 60) {
            return 88;
        }
        if (avgDelaySec <= 120) {
            return 72;
        }
        if (avgDelaySec <= 300) {
            return 58;
        }
        return 45;
    }

    /** 表达充分度：50 字以上才谈得上「结论 + 依据 + 例子」，15 字以下基本等于敷衍。 */
    private static int richnessScore(double avgChars) {
        if (avgChars >= 80) {
            return 100;
        }
        if (avgChars >= 50) {
            return 88;
        }
        if (avgChars >= 30) {
            return 78;
        }
        if (avgChars >= 15) {
            return 62;
        }
        return 45;
    }

    /**
     * 加权综合分：口头禅 30%、句子 25%、节奏 20%、充分度 25%。
     *
     * <p>某项为 {@code null}（如没有时间戳）时按<b>已有项重新归一化</b>，
     * 而不是把缺项按 0 分算——后者会让「本场没有时间戳」直接变成一次低分。
     */
    private static int weightedOverall(List<Metric> metrics) {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("filler", 0.30);
        weights.put("sentence", 0.25);
        weights.put("pace", 0.20);
        weights.put("richness", 0.25);

        double weightedSum = 0;
        double weightSum = 0;
        for (Metric metric : metrics) {
            Double weight = weights.get(metric.code());
            if (weight == null || metric.score() == null) {
                continue;
            }
            weightedSum += weight * metric.score();
            weightSum += weight;
        }
        return weightSum == 0 ? 0 : clamp((int) Math.round(weightedSum / weightSum), 0, 100);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

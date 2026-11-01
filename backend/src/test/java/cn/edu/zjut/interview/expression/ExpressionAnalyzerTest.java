package cn.edu.zjut.interview.expression;

import cn.edu.zjut.interview.dto.ExpressionReport;
import cn.edu.zjut.interview.dto.ExpressionReport.Metric;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表达分析的算分规则测试（文本版）。
 *
 * <p>断言一律写成<b>区间或相对关系</b>，不写死某个具体分数：评分公式将来调整阈值时，
 * 「口头禅多应该比口头禅少低」「没有时间戳不该被判 0 分」这些性质必须仍然成立，
 * 而钉死数字的测试只会在每次调参时被改掉，等于没有保护。
 */
class ExpressionAnalyzerTest {

    private static Metric metric(ExpressionReport report, String code) {
        return report.metrics().stream()
                .filter(m -> code.equals(m.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少指标 " + code));
    }

    @Test
    void cleanAnswerScoresHigherThanFillerHeavyAnswer() {
        ExpressionReport clean = ExpressionAnalyzer.analyze(1L, List.of(
                new ExpressionAnalyzer.Utterance(
                        "我认为可以从三个方面回答。第一是数据结构，第二是并发控制，第三是测试覆盖。", 20_000L),
                new ExpressionAnalyzer.Utterance(
                        "并发控制上我用了悲观锁。原因是写冲突比读冲突更常见，锁粒度放在行级。", 18_000L)));

        ExpressionReport fillerHeavy = ExpressionAnalyzer.analyze(2L, List.of(
                new ExpressionAnalyzer.Utterance("嗯嗯，那个，然后就是说，大概差不多就是这样。", 20_000L),
                new ExpressionAnalyzer.Utterance("嗯，那个，反正基本上就是加锁吧。", 18_000L)));

        Metric cleanFiller = metric(clean, "filler");
        Metric dirtyFiller = metric(fillerHeavy, "filler");
        assertTrue(cleanFiller.score() > dirtyFiller.score(),
                "干净的回答口头禅分应该更高：" + cleanFiller.score() + " vs " + dirtyFiller.score());
        assertEquals(0, clean.topFillers().size(), "没有填充词时 topFillers 应为空");
        assertTrue(fillerHeavy.topFillers().size() > 0, "填充词应被统计出来");
    }

    @Test
    void fillerWordsAreNotDoubleCounted() {
        // 「就是说」是长词，不能被拆成「就是」+「说」重复记账
        ExpressionReport report = ExpressionAnalyzer.analyze(3L, List.of(
                new ExpressionAnalyzer.Utterance("就是说这里要先做校验。", 10_000L),
                new ExpressionAnalyzer.Utterance("然后才会写数据库。", 10_000L)));

        List<String> fillers = report.topFillers();
        assertTrue(fillers.contains("就是说×1"), "长词应整词命中：" + fillers);
        assertTrue(fillers.contains("然后×1"), "独立出现的「然后」应被计入：" + fillers);
        assertEquals(2, fillers.size(), "不应产生额外的重复计数：" + fillers);
    }

    @Test
    void missingTimestampsLeavePaceScoreNullInsteadOfZero() {
        ExpressionReport report = ExpressionAnalyzer.analyze(4L, List.of(
                new ExpressionAnalyzer.Utterance("我先说结论，再说明依据和例子。", null),
                new ExpressionAnalyzer.Utterance("依据是线上压测的数据，例子是那次扩容。", null)));

        Metric pace = metric(report, "pace");
        assertNull(pace.score(), "没有时间戳时作答节奏应是「无法计算」而不是 0 分");
        assertEquals("无时间戳", pace.value());
        assertNotNull(metric(report, "filler").score());
        // 缺项后按已有项归一化，综合分不该被缺项拉低
        assertTrue(report.overallScore() > 0);
    }

    @Test
    void longIdleGapIsNotCountedAsThinkingTime() {
        // 20 分钟才回一句 = 挂机，不该把平均反应时长算成 600 秒
        ExpressionReport report = ExpressionAnalyzer.analyze(5L, List.of(
                new ExpressionAnalyzer.Utterance("第一题我先讲实现思路。", 20_000L),
                new ExpressionAnalyzer.Utterance("刚才那条是隔了很久才回的。", 20L * 60L * 1000L)));

        Metric pace = metric(report, "pace");
        assertNotNull(pace.score());
        assertTrue(pace.value().contains("20.0 秒"),
                "挂机间隔应被忽略，只剩 20 秒那条参与平均：" + pace.value());
    }

    @Test
    void shortFragmentAnswersGetLowerRichness() {
        ExpressionReport rich = ExpressionAnalyzer.analyze(6L, List.of(
                new ExpressionAnalyzer.Utterance("我认为核心在于把读写路径拆开，读走缓存写走队列，"
                        + "这样峰值流量下也不会互相拖慢，我们线上那次大促就是靠这个扛过去的。", 20_000L)));
        ExpressionReport thin = ExpressionAnalyzer.analyze(7L, List.of(
                new ExpressionAnalyzer.Utterance("加锁。", 20_000L)));

        assertTrue(metric(rich, "richness").score() > metric(thin, "richness").score(),
                "长回答的表达充分度应高于一句话回答");
    }

    @Test
    void overallScoreStaysWithinZeroToHundred() {
        ExpressionReport worst = ExpressionAnalyzer.analyze(8L, List.of(
                new ExpressionAnalyzer.Utterance("嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯嗯", 20_000L)));
        assertTrue(worst.overallScore() >= 0 && worst.overallScore() <= 100,
                "综合分必须落在 0-100：" + worst.overallScore());
        assertTrue(worst.sampleCount() == 1);
    }
}

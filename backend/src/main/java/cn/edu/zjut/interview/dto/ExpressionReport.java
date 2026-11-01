package cn.edu.zjut.interview.dto;

import java.util.List;

/**
 * 一场面试的表达分析结果（<b>本项目扩展（课设指标 ④）</b>）。
 *
 * <p><b>本批是文本版</b>：口头禅密度、句长波动、作答时长都只从「台词原文 + 台词时间戳」
 * 算出来，不依赖语音识别。语速、停顿、清晰度、自信度需要 ASR 的音频时轴与情感分析，
 * 等语音线（#8）落地后在同一个 DTO 里补几项 {@link Metric} 即可，前端不必改结构。
 *
 * <p>字段一律 camelCase（跨端契约），与 {@code EvaluationResult} 同一套命名习惯。
 *
 * @param sessionId    场次 id
 * @param sampleCount  参与统计的求职者发言条数（旁白 / 剧情提示不算作答）
 * @param overallScore 加权综合分（0-100）；没有任何可算指标时为 0
 * @param mode         数据来源，目前恒为 {@code text}（后续语音接入后会是 {@code text+audio}）
 * @param metrics      分项指标，顺序固定：filler / sentence / pace / richness
 * @param topFillers   出现最多的口头禅，形如 {@code 嗯×4}；没有则为空数组
 * @param suggestions  规则生成的改进建议（最多三条）
 * @param note         口径说明（告诉用户哪些指标还没有、为什么）
 */
public record ExpressionReport(
        Long sessionId,
        int sampleCount,
        int overallScore,
        String mode,
        List<Metric> metrics,
        List<String> topFillers,
        List<String> suggestions,
        String note) {

    /**
     * 单项表达指标。
     *
     * @param code    指标 code（filler / sentence / pace / richness），前端按它做排序与图标
     * @param label   中文标签
     * @param score   该项得分 0-100；{@code null} = 本场数据不足以计算（如没有时间戳时的作答节奏）
     * @param value   原始观测值的可读形式（如「2.6 次/百字」「42 秒」）
     * @param comment 一句话解释，写给用户看
     */
    public record Metric(String code, String label, Integer score, String value, String comment) {
    }
}

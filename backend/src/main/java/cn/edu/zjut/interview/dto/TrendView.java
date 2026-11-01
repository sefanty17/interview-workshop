package cn.edu.zjut.interview.dto;

import java.util.List;

/**
 * 成长曲线 + 提升路径的响应体（<b>本项目扩展（课设指标 ⑥）</b>）。
 *
 * <p>点按时间升序排列，前端直接按顺序画折线，不再自己排序。
 *
 * @param requiredPoints   画趋势线所需的最少场次数（低于它就显示「数据不足」）
 * @param points           只有剧本场次且已产出评估的场次才会出现
 * @param recommendations  按最近几场的短板维度推荐的知识库条目，可为空
 */
public record TrendView(
        int requiredPoints,
        List<TrendPoint> points,
        List<Recommendation> recommendations) {

    /**
     * 一场面试在曲线上的一个点。
     *
     * @param date     取结束时间（没有就取开始/创建时间），形如 {@code 2026-10-29 14:23:25}
     * @param jobCode  该场岗位，推荐语料按它限定范围
     * @param dimensions 四个维度，复用评估结果的 {@link EvaluationResult.DimensionScore}
     */
    public record TrendPoint(
            Long sessionId,
            String date,
            String title,
            String jobCode,
            Integer overallScore,
            List<EvaluationResult.DimensionScore> dimensions) {
    }

    /**
     * 一个短板维度的推荐清单。
     *
     * @param averageScore 最近几场该维度的平均分，用来解释「为什么推荐它」
     * @param query        实际拿去检索的查询串（维度评语优先），方便答辩时对着讲
     * @param hits         命中的知识库片段；知识库为空时是空数组
     */
    public record Recommendation(
            String dimCode,
            String label,
            int averageScore,
            String query,
            List<KnowledgeHit> hits) {
    }
}

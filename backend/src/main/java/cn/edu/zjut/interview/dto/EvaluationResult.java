package cn.edu.zjut.interview.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次评估的完整结果（<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>字段名一律 camelCase（跨端契约）：报告页（E2）直接消费这份 JSON，中间不做重命名，
 * 与 {@link ReplyEvent} 那类 SSE 事件保持同一套命名习惯。
 *
 * @param overallScore 加权总分 {@code round(Σ 各维度分 × 权重)}
 * @param dimensions   固定四个，顺序 technical / depth / logic / fit
 */
public record EvaluationResult(
        Long sessionId,
        String jobCode,
        Integer overallScore,
        String summary,
        List<String> strengths,
        List<String> weaknesses,
        List<String> suggestions,
        List<DimensionScore> dimensions,
        String model,
        LocalDateTime createdAt) {

    /**
     * 单个维度的分与评语。
     *
     * <p>与 {@code EvaluationResult} 放同一个文件而不是另开一个：它只在评估结果里出现，
     * 单独成文件会让人以为别处也能用。
     */
    public record DimensionScore(String code, String label, int score, String comment) {
    }
}

package cn.edu.zjut.interview.dto;

import java.time.LocalDateTime;

/**
 * {@code GET /api/sessions} 列表项（规格 B5 §6.1）。
 */
public record SessionSummaryView(
        Long id,
        String title,
        String status,
        Integer score,
        String jobCode,
        String scriptCode,
        String interviewerCode,
        LocalDateTime startedAt,
        LocalDateTime endedAt) {
}

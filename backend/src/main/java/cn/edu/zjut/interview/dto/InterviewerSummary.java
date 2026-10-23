package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.Interviewer;

/**
 * {@code GET /api/interviewers} 列表项（规格 B6 §1.1）。
 *
 * <p>只吐卡面字段，不含 {@code personaPrompt} 等长文本——对齐 LingChat 列表页的取数范围。
 */
public record InterviewerSummary(
        String code,
        String name,
        String subtitle,
        String cardTitle,
        String avatarMode,
        Double scale,
        Double offsetX,
        Double offsetY,
        Integer bubbleTop,
        Integer bubbleLeft,
        String displayRole,
        String opening,
        Integer sortOrder) {

    public static InterviewerSummary from(Interviewer e) {
        return new InterviewerSummary(
                e.getCode(),
                e.getName(),
                e.getSubtitle(),
                e.getCardTitle(),
                e.getAvatarMode(),
                e.getScale(),
                e.getOffsetX(),
                e.getOffsetY(),
                e.getBubbleTop(),
                e.getBubbleLeft(),
                e.getDisplayRole(),
                e.getOpening(),
                e.getSortOrder());
    }
}

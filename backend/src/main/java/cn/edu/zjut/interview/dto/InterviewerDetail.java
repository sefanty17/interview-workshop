package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.Interviewer;

/**
 * {@code GET /api/interviewers/{code}} 详情（规格 B6 §1.1）。
 *
 * <p>{@link InterviewerSummary} 的全部字段 + 长文本与称呼/语速等对话参数。
 */
public record InterviewerDetail(
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
        Integer sortOrder,
        String cardInfo,
        String personaPrompt,
        String exampleDialogue,
        String systemPromptExampleOld,
        String thinkingMessage,
        String userAlias,
        String userAliasSubtitle,
        Integer followUp,
        Integer speed) {

    public static InterviewerDetail from(Interviewer e) {
        return new InterviewerDetail(
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
                e.getSortOrder(),
                e.getCardInfo(),
                e.getPersonaPrompt(),
                e.getExampleDialogue(),
                e.getSystemPromptExampleOld(),
                e.getThinkingMessage(),
                e.getUserAlias(),
                e.getUserAliasSubtitle(),
                e.getFollowUp(),
                e.getSpeed());
    }
}

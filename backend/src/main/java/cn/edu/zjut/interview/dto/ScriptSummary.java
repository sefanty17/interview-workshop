package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.InterviewScript;

import java.util.List;

/**
 * {@code GET /api/scripts} 列表项（规格 B6 §1.1）。
 *
 * <p>{@code interviewerCode} 由 {@code interviewerId} 反查角色卡得到；关联不上时为 null。
 * {@code characters} 是本剧本的角色目录名，对齐 LingChat 的 {@code characters/}。
 */
public record ScriptSummary(
        String code,
        String scriptName,
        String introChapter,
        String description,
        String recommandStart,
        Integer orderNum,
        String triggerMode,
        String jobCode,
        String level,
        String companyStyle,
        String scriptSettings,
        String interviewerCode,
        List<String> characters) {

    public static ScriptSummary from(InterviewScript e, String interviewerCode) {
        return new ScriptSummary(
                e.getCode(),
                e.getScriptName(),
                e.getIntroChapter(),
                e.getDescription(),
                e.getRecommandStart(),
                e.getOrderNum(),
                e.getTriggerMode(),
                e.getJobCode(),
                e.getLevel(),
                e.getCompanyStyle(),
                e.getScriptSettings(),
                interviewerCode,
                ScriptCharacters.parse(e.getCharacters()));
    }
}

package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.InterviewScript;

import java.util.List;

/**
 * {@code GET /api/scripts/{code}} 详情（规格 B6 §1.1）：列表字段 + 全部章节（按 order_num 升序）。
 */
public record ScriptDetail(
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
        List<String> characters,
        List<ChapterView> chapters) {

    public static ScriptDetail from(InterviewScript e, String interviewerCode, List<ChapterView> chapters) {
        return new ScriptDetail(
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
                ScriptCharacters.parse(e.getCharacters()),
                chapters);
    }
}

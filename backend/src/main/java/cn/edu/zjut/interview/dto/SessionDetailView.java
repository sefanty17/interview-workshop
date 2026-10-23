package cn.edu.zjut.interview.dto;

import java.util.List;

/**
 * {@code GET /api/sessions/{id}} 的返回体（规格 B5 §6.1）。
 *
 * @param id             场次 id
 * @param title          标题
 * @param status         running / finished
 * @param score          评分（未评为 null）
 * @param jobCode        岗位编码
 * @param kps            知识点（逗号分隔，可空）
 * @param currentChapter 当前章节 code（剧本引擎进度）
 * @param eventSequence  当前章节内的事件下标（剧本引擎进度）
 * @param scriptCode     剧本编码
 * @param interviewerCode 面试官编码
 * @param messages       全部台词（按 seq 升序）
 */
public record SessionDetailView(
        Long id,
        String title,
        String status,
        Integer score,
        String jobCode,
        String kps,
        String currentChapter,
        Integer eventSequence,
        String scriptCode,
        String interviewerCode,
        List<SessionMessageView> messages) {
}

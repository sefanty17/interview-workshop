package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.InterviewChapter;

/**
 * 章节视图（规格 B6 §1.1）。
 *
 * <p>{@code events} 是库里原样的 JSON 数组字符串，不在这里反序列化，交给前端解析。
 */
public record ChapterView(
        String code,
        String name,
        Integer orderNum,
        String events) {

    public static ChapterView from(InterviewChapter e) {
        return new ChapterView(e.getCode(), e.getName(), e.getOrderNum(), e.getEvents());
    }
}

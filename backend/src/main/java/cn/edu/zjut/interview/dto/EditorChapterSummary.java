package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 章节列表项。{@code group} 是 {@code id} 里第一个 {@code /} 之前的那一段（原版 {@code chapter_summaries}），
 * 没有 {@code /} 时为 {@code null}。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EditorChapterSummary(
        String id,
        String name,
        String group,
        int eventCount) {
}

package cn.edu.zjut.interview.editor;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 章节图的一条边，从某章最后一条 {@code chapter_end} 反推而来（原版 {@code validate.rs:160-173}）。
 *
 * <p>导出给前端画真连线。在这之前流程图只是把章节按文件名字典序排了一列，
 * 箭头表达的是「章节 id 的字母顺序」而不是真实跳转 —— 看起来对但完全不对。
 *
 * @param from    来源章节 id
 * @param to      目标章节 id；{@code "end"} 表示剧本结束
 * @param isEnd   是否是显式的「剧本结束」
 * @param label   分支条件 / AI 分支名，linear 时为空串（空串不输出）
 * @param endType 该边所属章节的 end_type，前端据此决定能否拖拽重排
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ChapterEdge(
        String from,
        String to,
        boolean isEnd,
        String label,
        String endType) {
}

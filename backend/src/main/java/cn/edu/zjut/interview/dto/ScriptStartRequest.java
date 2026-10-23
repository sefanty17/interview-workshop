package cn.edu.zjut.interview.dto;

/**
 * {@code POST /api/sessions/{id}/script/start} 的请求体（规格 F-1 §B3，规格 E5b §1.2）。
 *
 * @param scriptCode  要开演的剧本编码，对应 {@code interview_script.code}；必填且必须存在
 * @param fromChapter 起始章节 id，对应 {@code interview_chapter.code}；为 null/空时
 *                    从 {@code intro_chapter} 开始（对齐原版 {@code editor_start_preview}
 *                    的 {@code from_chapter: Option&lt;String&gt;}，试玩「从本章开始」靠它）
 */
public record ScriptStartRequest(String scriptCode, String fromChapter) {
}

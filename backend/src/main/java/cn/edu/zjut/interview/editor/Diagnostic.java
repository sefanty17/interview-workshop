package cn.edu.zjut.interview.editor;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一条诊断，对应原版 {@code validate.rs:52-110}。
 *
 * <p>原版 {@code code} 是 {@code &'static str}，这里按规格改成 {@code String}；其余四个
 * 可选字段序列化时为空即不输出（原版 {@code #[serde(skip_serializing_if = "Option::is_none")]}）。
 *
 * @param severity   级别
 * @param code       稳定的机器可读代码，前端可据此做跳转/过滤
 * @param message    给作者看的话（逐字照抄原版，不许改写）
 * @param chapter    章节 id；为 null 表示剧本级问题
 * @param eventIndex 事件下标（0 起）
 * @param field      出问题的字段名
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Diagnostic(
        Severity severity,
        String code,
        String message,
        String chapter,
        Integer eventIndex,
        String field) {

    /** 剧本级问题。 */
    public static Diagnostic script(Severity severity, String code, String message) {
        return new Diagnostic(severity, code, message, null, null, null);
    }

    /** 章节级问题。 */
    public static Diagnostic chapter(Severity severity, String code, String chapter, String message) {
        return new Diagnostic(severity, code, message, chapter, null, null);
    }

    /** 事件级问题。 */
    public static Diagnostic event(Severity severity, String code, String chapter, int index, String message) {
        return new Diagnostic(severity, code, message, chapter, index, null);
    }

    /** 原版 {@code with_field}：补上字段名后返回自身。 */
    public Diagnostic withField(String field) {
        return new Diagnostic(severity, code, message, chapter, eventIndex, field);
    }
}

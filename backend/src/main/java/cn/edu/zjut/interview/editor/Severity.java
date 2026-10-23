package cn.edu.zjut.interview.editor;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 诊断级别。
 *
 * <p>原版是 {@code &'static str}，写错一个 "warning" 能编译、能序列化、排序落到兜底分支、
 * 前端当成 info —— 全链路静默。改成 enum 让编译器兜住。1:1 迁移自
 * {@code validate.rs:33-50}，序列化成小写的 {@code error} / {@code warn} / {@code info}。
 */
public enum Severity {

    ERROR("error"),
    WARN("warn"),
    INFO("info");

    private final String code;

    Severity(String code) {
        this.code = code;
    }

    /** JSON 里的小写取值（原版 {@code #[serde(rename_all = "lowercase")]}）。 */
    @JsonValue
    public String code() {
        return code;
    }

    /** 排序权重：error 最前。 */
    public int rank() {
        return switch (this) {
            case ERROR -> 0;
            case WARN -> 1;
            case INFO -> 2;
        };
    }
}

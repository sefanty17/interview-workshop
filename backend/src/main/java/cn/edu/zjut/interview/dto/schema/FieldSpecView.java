package cn.edu.zjut.interview.dto.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 一个字段该用什么控件渲染。对齐原版 {@code schema.rs::FieldSpec}。 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FieldSpecView(
        String key,
        String label,
        String kind,
        boolean required,
        String assetKind,
        java.util.List<String> options,
        java.util.List<String> optionLabels,
        String placeholder,
        String defaultDesc,
        String hint,
        boolean enabled) {
}

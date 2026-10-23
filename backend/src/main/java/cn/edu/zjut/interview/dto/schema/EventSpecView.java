package cn.edu.zjut.interview.dto.schema;

import java.util.List;

/** 一种事件。{@code typeKey} 就是 YAML 的 {@code type:} 值。 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
public record EventSpecView(
        String typeKey,
        String label,
        String category,
        String color,
        List<FieldSpecView> fields) {
}

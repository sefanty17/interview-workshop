package cn.edu.zjut.interview.dto.schema;

import java.util.List;

/** condition 语法说明，直接展示给作者。 */
public record ConditionSyntaxView(
        List<String> supported,
        List<String> unsupported,
        String note) {
}

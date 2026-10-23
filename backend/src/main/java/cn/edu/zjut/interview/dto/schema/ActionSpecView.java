package cn.edu.zjut.interview.dto.schema;

import java.util.List;

/** {@code choices} / {@code set_variable} 的 action 类型。 */
public record ActionSpecView(
        String typeKey,
        String label,
        String hint,
        List<String> allowedIn) {
}

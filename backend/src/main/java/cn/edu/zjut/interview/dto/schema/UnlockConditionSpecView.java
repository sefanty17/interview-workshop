package cn.edu.zjut.interview.dto.schema;

import java.util.List;

/** 羁绊冒险解锁条件类型。 */
public record UnlockConditionSpecView(
        String typeKey,
        String label,
        List<FieldSpecView> fields) {
}

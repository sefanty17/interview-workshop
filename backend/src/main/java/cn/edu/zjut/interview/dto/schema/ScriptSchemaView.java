package cn.edu.zjut.interview.dto.schema;

import java.util.List;

/** 编辑器 schema 根对象，对齐原版 {@code ScriptSchema}。 */
public record ScriptSchemaView(
        List<EventSpecView> events,
        List<FieldSpecView> commonFields,
        List<FieldSpecView> storyConfigFields,
        List<ActionSpecView> actionTypes,
        List<UnlockConditionSpecView> unlockConditionTypes,
        List<String> placeholderFields,
        ConditionSyntaxView conditionSyntax) {
}

package cn.edu.zjut.interview.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConditionEvaluator} 对齐 {@code events/mod.rs:186 evaluate_condition} 的六种情形。
 */
class ConditionEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyConditionIsTrue() {
        assertTrue(ConditionEvaluator.evaluate("", vars()));
        assertTrue(ConditionEvaluator.evaluate("   ", vars()));
    }

    @Test
    void equalsMatchesValue() {
        assertTrue(ConditionEvaluator.evaluate("mode == \"deep\"", vars("mode", "deep")));
    }

    @Test
    void equalsWithUndefinedVariableIsFalse() {
        assertFalse(ConditionEvaluator.evaluate("mode == \"deep\"", vars()));
    }

    @Test
    void notEqualsMatchesValue() {
        assertTrue(ConditionEvaluator.evaluate("mode != \"deep\"", vars("mode", "basic")));
    }

    @Test
    void notEqualsWithUndefinedVariableIsTrue() {
        assertTrue(ConditionEvaluator.evaluate("mode != \"deep\"", vars()));
    }

    @Test
    void booleanVariableIsReadAsTruthiness() {
        assertTrue(ConditionEvaluator.evaluate("passed", vars("passed", true)));
        assertFalse(ConditionEvaluator.evaluate("passed", vars("passed", false)));
        assertTrue(ConditionEvaluator.evaluate("note", vars("note", "写了点东西")));
        assertFalse(ConditionEvaluator.evaluate("nothing", vars()));
    }

    @Test
    void booleanComparedAsString() {
        // Rust 注释：flag == true 既能匹配 Bool(true) 也能匹配字符串 "true"
        assertTrue(ConditionEvaluator.evaluate("flag == true", vars("flag", true)));
    }

    @Test
    void unsupportedOperatorFallsBackToBareVariable() {
        // hp >= 5 不实现，落到裸变量分支，永远不存在 → false
        assertFalse(ConditionEvaluator.evaluate("hp >= 5", vars("hp", 9)));    }

    private ObjectNode vars() {
        return objectMapper.createObjectNode();
    }

    private ObjectNode vars(String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    private ObjectNode vars(String key, boolean value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    private ObjectNode vars(String key, int value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }
}

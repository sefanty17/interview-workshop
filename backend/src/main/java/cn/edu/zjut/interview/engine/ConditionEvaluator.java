package cn.edu.zjut.interview.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 剧本条件求值，对应 {@code script_engine/events/mod.rs:186 evaluate_condition}。
 *
 * <p>逐条照搬原版语义：
 * <ul>
 *   <li>空串 → {@code true}</li>
 *   <li>先 {@code !=}（更长的模式优先）：变量未定义 → {@code true}（未定义不持有任何值与任何值都不等）</li>
 *   <li>再 {@code ==}：变量未定义 → {@code false}</li>
 *   <li>都没有 → 当布尔变量查表：Bool 取自身，Null 为 false，其他非 null 视为真，未定义 false</li>
 * </ul>
 *
 * <p>比较一律按「字符串形式」做：字符串原样，其他类型走 {@link JsonNode#asText()}，
 * 所以 {@code flag == true} 既能匹配布尔 true 也能匹配字符串 "true"。
 * {@code >}、{@code <}、{@code &&}、{@code ||} 等原版没实现，这里也不实现。
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public static boolean evaluate(String condition, JsonNode vars) {
        if (condition == null) {
            return true;
        }
        String expression = condition.trim();
        if (expression.isEmpty()) {
            return true;
        }
        JsonNode table = vars == null ? null : vars;

        int ne = expression.indexOf("!=");
        if (ne >= 0) {
            String name = expression.substring(0, ne).trim();
            String value = stripQuotes(expression.substring(ne + 2).trim());
            JsonNode current = table == null ? null : table.get(name);
            if (current == null || current.isMissingNode()) {
                return true;
            }
            return !stringOf(current).equals(value);
        }

        int eq = expression.indexOf("==");
        if (eq >= 0) {
            String name = expression.substring(0, eq).trim();
            String value = stripQuotes(expression.substring(eq + 2).trim());
            JsonNode current = table == null ? null : table.get(name);
            if (current == null || current.isMissingNode()) {
                return false;
            }
            return stringOf(current).equals(value);
        }

        JsonNode current = table == null ? null : table.get(expression);
        if (current == null || current.isMissingNode()) {
            return false;
        }
        if (current.isBoolean()) {
            return current.booleanValue();
        }
        if (current.isNull()) {
            return false;
        }
        return true;
    }

    /** Rust: {@code val.trim().trim_matches('"').trim_matches('\'')}。 */
    private static String stripQuotes(String value) {
        String s = value;
        while (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        while (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Rust: {@code Value::String(s) => s.clone(), other => other.to_string()}。 */
    private static String stringOf(JsonNode node) {
        return node.isTextual() ? node.textValue() : node.toString();
    }
}

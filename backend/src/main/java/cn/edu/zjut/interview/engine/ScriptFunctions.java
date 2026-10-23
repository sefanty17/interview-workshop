package cn.edu.zjut.interview.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剧本静态工具，对应 {@code script_engine/utils/script_function.rs}。
 *
 * <p>搬本引擎真正用到的几组函数：
 * <ul>
 *   <li>{@link #matchAiResponseOptions}（{@code script_function.rs:205}）</li>
 *   <li>{@link #replacePlaceholder}（{@code script_function.rs:20}）</li>
 *   <li>{@link #parseVariableAction} / {@link #parseValue} /
 *       {@link #applyVariableAction}（{@code script_function.rs:67-200}），
 *       给 {@code set_variable} 事件与选项动作共用</li>
 * </ul>
 *
 * <p><b>与原版的差异</b>：Rust 的 {@code replace_placeholder(text, game_status)} 需要全局
 * {@code GameStatus} 读 {@code player.user_name}；本项目没有进程内的 game_status，
 * 用户昵称来自 {@code user_profile.nickname}，因此退化为单参数
 * {@code replacePlaceholder(text, playerName)}。占位符表原版只有 {@code %player%} 一项，
 * 这里也只有一个，语义不变。
 */
public final class ScriptFunctions {

    /** 唯一的占位符（Rust: {@code text.replace("%player%", user_name)}）。 */
    public static final String PLAYER_PLACEHOLDER = "%player%";

    /**
     * 导入器把剧本里的 {@code %player%} 归一成 {@code {user}}
     * （{@code ScriptYamlImporter#replacePlaceholders}，规格 B5-BE §2.2），所以引擎真正会碰到的是
     * 这个写法；两者同义，都指玩家。
     */
    public static final String USER_PLACEHOLDER = "{user}";

    /** {@code {user}} 在 {@code user_profile} 没有昵称时的取值：站在玩家视角叙述。 */
    public static final String USER_FALLBACK = "你";

    private ScriptFunctions() {
    }

    /**
     * 把 LLM 的回复与命名选项做忽略大小写的子串匹配，返回命中项的 {@code next}。
     *
     * <p>先按顺序扫 {@code name} 非空且被回复包含的选项；一轮全不命中时，
     * 再扫 {@code default == true} 的选项兜底；仍没有则 {@code null}。
     */
    public static String matchAiResponseOptions(String aiResponse, JsonNode options) {
        String response = aiResponse == null ? "" : aiResponse.trim().toLowerCase();
        if (options == null || !options.isArray()) {
            return null;
        }

        for (JsonNode option : options) {
            String name = option.path("name").asText("");
            if (!name.isEmpty() && response.contains(name.toLowerCase())) {
                JsonNode next = option.get("next");
                return next != null && next.isTextual() ? next.asText() : null;
            }
        }

        for (JsonNode option : options) {
            if (option.path("default").asBoolean(false)) {
                JsonNode next = option.get("next");
                return next != null && next.isTextual() ? next.asText() : null;
            }
        }
        return null;
    }

    /** 把 {@code %player%} 换成用户昵称。 */
    public static String replacePlaceholder(String text, String playerName) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace(PLAYER_PLACEHOLDER, playerName == null ? "" : playerName);
    }

    /**
     * 把 {@code %player%} 与 {@code {user}} 一起换掉。
     *
     * @param playerName {@code %player%} 的取值（原版 {@code game_status.player.user_name}）
     * @param userName   {@code {user}} 的取值（{@code user_profile.nickname}）；为空时用 {@link #USER_FALLBACK}
     */
    public static String replacePlaceholders(String text, String playerName, String userName) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String user = userName == null || userName.isBlank() ? USER_FALLBACK : userName;
        return text.replace(PLAYER_PLACEHOLDER, playerName == null ? "" : playerName)
                .replace(USER_PLACEHOLDER, user);
    }

    // ───────────────────────── 变量动作（script_function.rs:59-200） ─────────────────────────

    /** 变量操作符，对应 Rust {@code VarOp}。 */
    public enum VarOp {
        ASSIGN, ADD, SUB
    }

    /**
     * 解析结果，对应 Rust 的 {@code (VarOp, String, Value)} 三元组。
     *
     * @param op    操作符
     * @param name  变量名
     * @param value 右值（已按 {@link #parseValue} 转成 JSON 值）
     */
    public record VariableAction(VarOp op, String name, JsonNode value) {
    }

    /** Rust: {@code ^\s*(\w+)\s*(=|\+=|-=)\s*(.+?)\s*$}；{@code \w} 与原版一样按 Unicode 匹配。 */
    private static final Pattern VARIABLE_ACTION =
            Pattern.compile("^\\s*(\\w+)\\s*(=|\\+=|-=)\\s*(.+?)\\s*$", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 解析 {@code "flag = true"} / {@code "count += 1"} / {@code "hp -= 5"}。
     *
     * @return 解析结果；无法解析（或操作符不认识）时返回 {@code null}，调用方记 warn
     *         （原版返回 {@code Err}，这里用 null 表示，语义等价）
     */
    public static VariableAction parseVariableAction(String action) {
        if (action == null) {
            return null;
        }
        Matcher matcher = VARIABLE_ACTION.matcher(action);
        if (!matcher.matches()) {
            return null;
        }
        VarOp op = switch (matcher.group(2)) {
            case "=" -> VarOp.ASSIGN;
            case "+=" -> VarOp.ADD;
            case "-=" -> VarOp.SUB;
            default -> null;
        };
        if (op == null) {
            return null;
        }
        JsonNode value = parseValue(matcher.group(3).trim());
        if (value == null) {
            return null;
        }
        return new VariableAction(op, matcher.group(1), value);
    }

    /**
     * 把字符串字面量解析成 JSON 值（bool / int / float / string），支持 {@code random(min, max)}。
     *
     * <p>{@code random} 两端闭区间一次性取整（原版曾经返回中点常量，已修）；
     * 无法识别的形态原样当字符串，并剥掉成对的引号。
     */
    public static JsonNode parseValue(String raw) {
        String text = raw == null ? "" : raw.trim();

        if (text.startsWith("random(") && text.endsWith(")")) {
            String inner = text.substring("random(".length(), text.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length == 2) {
                try {
                    long min = Long.parseLong(parts[0].trim());
                    long max = Long.parseLong(parts[1].trim());
                    if (max < min) {
                        return null;
                    }
                    // ThreadLocalRandom 的上界是开区间；max = Long.MAX_VALUE 时退化成开区间取整
                    long value = max == Long.MAX_VALUE
                            ? ThreadLocalRandom.current().nextLong(min, max)
                            : ThreadLocalRandom.current().nextLong(min, max + 1);
                    return LongNode.valueOf(value);
                } catch (NumberFormatException ignored) {
                    // 落到字符串分支，与原版 parse 失败即当字符串一致
                }
            }
        }

        if (text.equalsIgnoreCase("true")) {
            return BooleanNode.TRUE;
        }
        if (text.equalsIgnoreCase("false")) {
            return BooleanNode.FALSE;
        }
        if (text.equalsIgnoreCase("null") || text.equalsIgnoreCase("none")) {
            return NullNode.getInstance();
        }

        try {
            return LongNode.valueOf(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            // 继续试浮点
        }
        try {
            double value = Double.parseDouble(text);
            // serde_json::Number::from_f64 对 NaN / Infinity 返回 None（原版随后当字符串）
            if (Double.isFinite(value)) {
                return DoubleNode.valueOf(value);
            }
        } catch (NumberFormatException ignored) {
            // 落到字符串
        }

        String inner = text;
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            inner = text.substring(1, text.length() - 1);
        } else if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            inner = text.substring(1, text.length() - 1);
        }
        return TextNode.valueOf(inner);
    }

    /**
     * 对当前值施加一次变量操作，返回新值。
     *
     * <p>{@code +=} / {@code -=} 只在两侧都是数字时做算术，且整数保持整数（原版注释：
     * 先试 f64 会让 {@code 1 += 1} 变成 {@code 2.0}，随后的 {@code count == 2} 就永远不成立）；
     * 其余情况退化成直接赋值，整数溢出回落到浮点。
     */
    public static JsonNode applyVariableAction(VarOp op, JsonNode current, JsonNode value) {
        return switch (op) {
            case ASSIGN -> value;
            case ADD -> arithmetic(current, value, true);
            case SUB -> arithmetic(current, value, false);
        };
    }

    private static JsonNode arithmetic(JsonNode current, JsonNode value, boolean add) {
        if (current != null && current.isNumber() && value != null && value.isNumber()) {
            if (current.canConvertToLong() && value.canConvertToLong()) {
                try {
                    long a = current.longValue();
                    long b = value.longValue();
                    return LongNode.valueOf(add ? Math.addExact(a, b) : Math.subtractExact(a, b));
                } catch (ArithmeticException overflow) {
                    // 溢出不抛出，落到浮点分支，与原版 checked_add 返回 None 后试 f64 一致
                }
            }
            double a = current.doubleValue();
            double b = value.doubleValue();
            return DoubleNode.valueOf(add ? a + b : a - b);
        }
        return value;
    }
}

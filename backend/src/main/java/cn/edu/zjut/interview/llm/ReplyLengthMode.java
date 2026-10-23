package cn.edu.zjut.interview.llm;

import java.util.Locale;

/**
 * 回复台词数量档位（1:1 对齐 {@code utils/prompt.rs:398-507}，逐字照抄）。
 *
 * <p>变量缺失时即「正常」，与系统提示词里写死的 3~5 句一致。
 * 对应物说明：原版把档位存在「存档全局变量 {@code reply_length.mode}」里，
 * 本项目的对应物是 {@code interview_session.reply_length_mode} 行（跟随会话生命周期）。
 */
public enum ReplyLengthMode {

    /** 短：目标 1~2 句。 */
    SHORT,
    /** 正常：目标 3~5 句。 */
    NORMAL,
    /** 自由：不限制、不检查。 */
    FREE;

    /** 存进会话行的取值（原版：存进存档全局变量的取值）。 */
    public String asStr() {
        return switch (this) {
            case SHORT -> "short";
            case NORMAL -> "normal";
            case FREE -> "free";
        };
    }

    /** 解析会话行里的取值；缺失或非法一律回落「正常」。 */
    public static ReplyLengthMode fromStrLossy(String value) {
        if (value == null) {
            return NORMAL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "short" -> SHORT;
            case "free" -> FREE;
            default -> NORMAL;
        };
    }

    /** 解析 HTTP 入参：null / 非法值抛 {@link IllegalArgumentException}（controller 转 400）。 */
    public static ReplyLengthMode parseStrict(String value) {
        if (value == null) {
            throw new IllegalArgumentException("未知的回复台词数量档位：null");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "short" -> SHORT;
            case "normal" -> NORMAL;
            case "free" -> FREE;
            default -> throw new IllegalArgumentException("未知的回复台词数量档位：" + value);
        };
    }

    /**
     * 容忍区间（台词条数）：AI 输出不稳定，落在区间内即认为满足要求。null = 不检查。
     */
    public int[] acceptedRange() {
        return switch (this) {
            case SHORT -> new int[] {1, 3};
            case NORMAL -> new int[] {2, 6};
            case FREE -> null;
        };
    }

    /** 档位变更时插入的系统提示台词。 */
    public String announceText() {
        return switch (this) {
            case SHORT -> "从现在起，你的每次回复只需要 1~2 句台词，请不要超过三句。这条要求覆盖之前关于台词数量的所有要求。";
            case NORMAL -> "从现在起，你的每次回复请控制在 3~5 句台词之间，不要少于两句、也不要超过六句。这条要求覆盖之前关于台词数量的所有要求。";
            case FREE -> "从现在起，不再限制你的回复台词数量，你可以完全根据对话需求决定说多少句。这条要求覆盖之前关于台词数量的所有要求。";
        };
    }

    /**
     * 回复条数越界时的纠偏提示；在容忍区间内或自由档返回 null。
     */
    public String correctionText(int actual) {
        int[] range = acceptedRange();
        if (range == null) {
            return null;
        }
        int min = range[0];
        int max = range[1];
        if (actual >= min && actual <= max) {
            return null;
        }
        String target = this == SHORT ? "1~2" : "3~5";
        return actual > max
                ? "你上一条回复有 " + actual + " 句台词，偏多了。下一次回复请压缩到 " + target + " 句。"
                : "你上一条回复只有 " + actual + " 句台词，偏少了。下一次回复请扩展到 " + target + " 句。";
    }
}

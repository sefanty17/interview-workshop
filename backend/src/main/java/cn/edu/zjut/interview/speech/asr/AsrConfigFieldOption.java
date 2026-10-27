package cn.edu.zjut.interview.speech.asr;

/**
 * {@link AsrConfigFieldKind#SELECT} 的一个选项，字段名照上游 {@code AsrConfigFieldOption}。
 *
 * @param value 写进配置的值
 * @param label UI 显示名
 */
public record AsrConfigFieldOption(String value, String label) {
}

package cn.edu.zjut.interview.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个设置项的定义 + 当前值，对应原版 {@code config/types.rs} 的 {@code ConfigSetting}
 * 并补上分组、类型、范围、默认值（原版这些信息散落在 {@code tree.rs} 的字面量里）。
 *
 * <p>{@code value} 一律是 <b>String</b>：原版 {@code save_settings(values: BTreeMap<String,String>)}
 * 存的就是字符串，前端按 {@code type} 自己转。
 *
 * @param key          点号命名的配置键（{@link ConfigKey}）
 * @param group        逻辑分组 id：{@code llm / features / chat / display}（配置树的分类另见 {@link ConfigTree}）
 * @param label        中文名，校验报错时也用它（「温度必须在 0.0–2.0 之间」）
 * @param type         值类型：{@code text / number / bool}（与原版 {@code setting_type} 一致，全小写）
 * @param value        当前值；注册表里的定义没有这一项，{@code buildTree} 时按 DB 或默认值填
 * @param defaultValue 默认值（单一真相源）
 * @param min          最小值（number 才有）
 * @param max          最大值（number 才有）
 * @param options      带枚举语义的 text 项的合法取值（第一批 17 个里没有）
 * @param description  说明文案
 * @param extension    是否本项目扩展（原版没有的 key，标 true）
 * @param integral     number 项是否只接受整数（<b>内部标记，不进 JSON</b>）。原版没有这个字段：
 *                     原版 number 一律当浮点，整数约束写在校验里；本项目原来的 {@code INT}/{@code DOUBLE}
 *                     两个类型合并成 {@code number} 后，靠它保住「整数键不接受小数」的行为。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigSetting(
        String key,
        String group,
        String label,
        String type,
        String value,
        String defaultValue,
        Double min,
        Double max,
        List<String> options,
        String description,
        boolean extension,
        @JsonIgnore boolean integral) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_BOOL = "bool";

    /** 填上当前值，返回新实例（record 不可变，注册表里的定义要保持干净）。 */
    public ConfigSetting withValue(String currentValue) {
        return new ConfigSetting(key, group, label, type, currentValue, defaultValue,
                min, max, options, description, extension, integral);
    }

    /** 整数项：{@code min..max} 闭区间，提交小数会被拒。 */
    public static ConfigSetting intSetting(String key, String group, String label, int defaultValue,
                                           int min, int max, String description, boolean extension) {
        return new ConfigSetting(key, group, label, TYPE_NUMBER, null, String.valueOf(defaultValue),
                (double) min, (double) max, null, description, extension, true);
    }

    /** 小数项：{@code min..max} 闭区间。 */
    public static ConfigSetting doubleSetting(String key, String group, String label, double defaultValue,
                                              double min, double max, String description, boolean extension) {
        return new ConfigSetting(key, group, label, TYPE_NUMBER, null, String.valueOf(defaultValue),
                min, max, null, description, extension, false);
    }

    /** 布尔项。 */
    public static ConfigSetting boolSetting(String key, String group, String label, boolean defaultValue,
                                            String description, boolean extension) {
        return new ConfigSetting(key, group, label, TYPE_BOOL, null, String.valueOf(defaultValue),
                null, null, null, description, extension, false);
    }

    /** 文本项。 */
    public static ConfigSetting stringSetting(String key, String group, String label, String defaultValue,
                                              String description, boolean extension) {
        return new ConfigSetting(key, group, label, TYPE_TEXT, null, defaultValue,
                null, null, null, description, extension, false);
    }

    /**
     * 带枚举语义的文本项（第一批 17 个里没有，留着给后续批次）：
     * 原版没有 ENUM 类型，这里类型仍是 {@code text}，合法取值由 {@code options} 约束。
     */
    public static ConfigSetting enumSetting(String key, String group, String label, String defaultValue,
                                            List<String> options, String description, boolean extension) {
        return new ConfigSetting(key, group, label, TYPE_TEXT, null, defaultValue,
                null, null, List.copyOf(options), description, extension, false);
    }
}

package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * provider 配置字段类型，前端设置页据此决定渲染哪种输入控件。
 *
 * <p>JSON 值的写法（snake_case）直接照抄上游 serde 的
 * {@code #[serde(rename_all = "snake_case")]}，前端按字符串比较，写错就渲染成空白控件。
 */
public enum AsrConfigFieldKind {

    /** 普通文本。 */
    TEXT("text"),
    /** 密码框（API Key 等敏感字段）。 */
    PASSWORD("password"),
    /** 整数。 */
    NUMBER("number"),
    /** 布尔开关。 */
    BOOLEAN("boolean"),
    /** 下拉单选（配合 {@link AsrConfigField#options()}，如地域选择）。 */
    SELECT("select"),

    /**
     * 密码框，但值按地域分开存（{@code provider_configs[id].api_keys[地域id]}）。
     *
     * <p>UI 只显示当前地域的那一个框，存储却是每个地域一份——各地域的 Key
     * 互相独立、不能混用。前端需要的当前地域取自 {@code ProviderInfo.regions}
     * 加配置里的 {@code region}，因此本类型不需要自己的 options。
     */
    PASSWORD_MAP("password_map");

    private final String wireName;

    AsrConfigFieldKind(String wireName) {
        this.wireName = wireName;
    }

    /** 序列化到 JSON 的字符串。 */
    @JsonValue
    public String wireName() {
        return wireName;
    }
}

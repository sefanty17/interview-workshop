package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * provider 在设置页上需要填写的字段，字段名与 JSON 键照上游 {@code AsrConfigField}。
 *
 * <p>{@code key} 会被前端写成 {@code provider_configs[id]} 的<strong>顶层键</strong>，
 * 所以它必须与 {@link AsrProviderConfig} 里 {@code @JsonProperty} 的值逐字一致；
 * 只加元数据不加字段，用户填的值会被忽略（上游注释专门警告过这一点）。
 *
 * @param key          字段键（写入 {@code provider_configs[id].<key>}）
 * @param label        字段显示名（前端可自行 i18n）
 * @param kind         控件类型
 * @param required     是否必填
 * @param defaultValue 默认值（字符串形式；{@code null} = 无静态默认值）
 * @param placeholder  占位提示
 * @param hint         输入框下方的说明，前端以纯文本渲染，不能写 Markdown 记号
 * @param options      {@link AsrConfigFieldKind#SELECT} 的选项；其它类型为空
 */
public record AsrConfigField(
        String key,
        String label,
        AsrConfigFieldKind kind,
        boolean required,
        @JsonProperty("default_value") String defaultValue,
        String placeholder,
        String hint,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AsrConfigFieldOption> options) {

    public AsrConfigField {
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** 普通文本框。 */
    public static AsrConfigField text(String key, String label, String placeholder, String hint) {
        return new AsrConfigField(key, label, AsrConfigFieldKind.TEXT, false, null, placeholder, hint, List.of());
    }

    /** 密码框（敏感字段）。 */
    public static AsrConfigField password(String key, String label, String placeholder, String hint) {
        return new AsrConfigField(key, label, AsrConfigFieldKind.PASSWORD, false, null, placeholder, hint, List.of());
    }

    /** 按地域分开存的密码框。 */
    public static AsrConfigField passwordMap(String key, String label, String placeholder, String hint) {
        return new AsrConfigField(key, label, AsrConfigFieldKind.PASSWORD_MAP, true, null, placeholder, hint,
                List.of());
    }

    /** 下拉单选。 */
    public static AsrConfigField select(String key, String label, String defaultValue, String hint,
                                        List<AsrConfigFieldOption> options) {
        return new AsrConfigField(key, label, AsrConfigFieldKind.SELECT, true, defaultValue, null, hint, options);
    }
}

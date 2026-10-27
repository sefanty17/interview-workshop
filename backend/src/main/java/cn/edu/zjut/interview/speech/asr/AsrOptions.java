package cn.edu.zjut.interview.speech.asr;

import java.util.List;

/**
 * 一次识别调用的可选参数，对齐 {@code provider_types.rs::AsrOptions}。
 *
 * <p>为什么不把这两个参数塞进 {@link AsrProviderConfig}：热词与语言提示的来源是
 * <strong>当前角色</strong>，随角色切换而变；而 provider 配置是用户级的、
 * 一次配好长期不变。混在一起会导致「切换角色后热词没变」。
 *
 * @param languageHint 可选 BCP-47 语言码（{@code zh} / {@code en} / {@code ja}）。
 *                     注意这是<strong>提示</strong>而非结果：{@link AsrResult#language()}
 *                     只填 provider 真正检测到的语言，别把提示回填进去。
 * @param hotwords     本次调用的热词，空列表 = 无热词（没有配置级回退）
 */
public record AsrOptions(String languageHint, List<AsrHotword> hotwords) {

    public AsrOptions {
        hotwords = hotwords == null ? List.of() : List.copyOf(hotwords);
    }

    /** 什么都不指定：V1 的探活与 mock 识别用它。 */
    public static AsrOptions empty() {
        return new AsrOptions(null, List.of());
    }
}

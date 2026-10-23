package cn.edu.zjut.interview.llm;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;

/**
 * 一次请求的生成参数，全部来自 {@link ConfigService}（规格 B4-BE §三、S-2+3 §A4）。
 *
 * <p>baseUrl / apiKey 仍然留在 yml（{@code LlmProperties}）——那是连接凭据，不是用户可调的设置项；
 * 模型名、温度、top_p、max_tokens、超时这 5 个才是「设置页能改、后端立刻生效」的部分，
 * 五个字段都直接进请求体 / HttpRequest（对应 {@code ConfigService.CONSUMED_KEYS}）。
 *
 * @param model          模型名（{@code llm.model}）
 * @param temperature    采样温度（{@code llm.temperature}）
 * @param topP           核采样阈值（{@code llm.top_p}）
 * @param maxTokens      最大输出 token（{@code llm.max_tokens}）
 * @param timeoutSeconds 请求超时秒数（{@code llm.timeout_secs}）
 */
public record LlmOptions(String model, double temperature, double topP, int maxTokens, int timeoutSeconds) {

    /** 按当前设置组装参数；每次调用都读一遍，所以设置页改完下一轮对话就生效。 */
    public static LlmOptions from(ConfigService config) {
        return new LlmOptions(
                config.get(ConfigKey.LLM_MODEL),
                config.getDouble(ConfigKey.LLM_TEMPERATURE),
                config.getDouble(ConfigKey.LLM_TOP_P),
                config.getInt(ConfigKey.LLM_MAX_TOKENS),
                config.getInt(ConfigKey.LLM_TIMEOUT_SECS));
    }
}

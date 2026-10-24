package cn.edu.zjut.interview.llm;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;

import java.util.function.Consumer;

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
 * @param thinking       思考模式覆盖；{@code null} = 调用方没指定，请求体按「关」下发
 *                       （对齐原版 {@code LlmProviderConfig.enable_thinking} 的 serde 默认 {@code false}）
 * @param onReasoning    思考链增量回调，可为 {@code null}。对齐 {@code LlmChunk::Reasoning}
 *                       （{@code llm/mod.rs:101-103}）：思考链只用于展示与统计，<b>不进正文</b>。
 *                       放在这里而不是加一个重载，是为了让 {@code stream} 的签名保持不变
 *                       （既有测试都按 3 参数打桩）。
 */
public record LlmOptions(String model, double temperature, double topP, int maxTokens, int timeoutSeconds,
                         Boolean thinking, Consumer<String> onReasoning) {

    /** 兼容旧签名：不带思考链回调。 */
    public LlmOptions(String model, double temperature, double topP, int maxTokens, int timeoutSeconds,
                      Boolean thinking) {
        this(model, temperature, topP, maxTokens, timeoutSeconds, thinking, null);
    }

    /** 按当前设置组装参数；每次调用都读一遍，所以设置页改完下一轮对话就生效。 */
    public static LlmOptions from(ConfigService config) {
        return new LlmOptions(
                config.get(ConfigKey.LLM_MODEL),
                config.getDouble(ConfigKey.LLM_TEMPERATURE),
                config.getDouble(ConfigKey.LLM_TOP_P),
                config.getInt(ConfigKey.LLM_MAX_TOKENS),
                config.getInt(ConfigKey.LLM_TIMEOUT_SECS),
                null);
    }

    /** 派生一份带思考链回调的参数（其余字段原样）。 */
    public LlmOptions withReasoning(Consumer<String> callback) {
        return new LlmOptions(model, temperature, topP, maxTokens, timeoutSeconds, thinking, callback);
    }

    /** 思考模式是否下发为 {@code enabled}；{@code null} 视作关闭。 */
    public boolean thinkingEnabled() {
        return Boolean.TRUE.equals(thinking);
    }
}

package cn.edu.zjut.interview.speech.tts;

/**
 * TTS 错误分类。
 *
 * <p>与 ASR 不同，这组错误码<strong>没有上游对应物</strong>：原版 TTS 用
 * {@code anyhow!} 拼字符串（见 {@code adapter/*.rs} 的 {@code "SBV2 请求失败: HTTP {}"}），
 * 根本没有码表。但前端设置页要做「配错了哪个字段」与「引擎没接」的区分，
 * 单词字符串办不到，所以这里自建一小组码——{@link #code()} 是稳定契约，别随手改字符。
 *
 * <p>码前缀统一 {@code TTS_}，与 ASR 的 {@code ASR_} 分属两组：前端把两类错误都塞进同一个
 * toast 组件，只靠前缀区分文案表。
 */
public enum TtsErrorKind {

    /** 引擎类型存在，但本项目还没接实现（edge-tts / local-sbv2 以及后续批次的引擎）。 */
    ENGINE_UNSUPPORTED("TTS_ENGINE_UNSUPPORTED", "语音引擎未接入"),
    /** 缺少必填参数：地址、密钥、音色。 */
    NOT_CONFIGURED("TTS_NOT_CONFIGURED", "语音引擎缺少必填配置"),
    /** 上游返回非 2xx，或网络层失败。 */
    REQUEST_FAILED("TTS_REQUEST_FAILED", "语音引擎请求失败"),
    /** 请求超时。 */
    TIMEOUT("TTS_TIMEOUT", "语音引擎请求超时"),
    /** 返回体为空 / 不是音频 / JSON 解析失败。 */
    BAD_RESPONSE("TTS_BAD_RESPONSE", "语音引擎返回内容无效"),
    /** 连续失败次数达到上限，TTS 已被自动禁用，需 {@code reactivate()} 恢复。 */
    DISABLED("TTS_DISABLED", "TTS 已因连续失败被禁用");

    private final String code;
    private final String defaultMessage;

    TtsErrorKind(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** 前端据此查文案；与 {@link TtsException} 的 {@code code} 字段逐字一致。 */
    public String code() {
        return code;
    }

    /** 中文兜底文案，抛点没给 message 时用它。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}

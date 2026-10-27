package cn.edu.zjut.interview.speech.asr;

/**
 * ASR 错误分类，对应原版 {@code ai_service/asr/error.rs} 的 {@code AsrError} 变体。
 *
 * <p>{@link #code()} 必须与上游 {@code i18n_code()} 逐字一致：前端 {@code parseAsrError}
 * 拿它去 locale 表里查人话，改一个字符就等于用户看到一片空白提示。
 *
 * <p>为什么在 Java 侧再存一份中文兜底文案：原版靠 {@code thiserror} 的
 * {@code #[error("...")]} 生成展示文案，Java 没有等价机制；保留它可以让日志与
 * 「前端还没实现 i18n」的场景都有话可说，而不用把同一句中文散落在抛点。
 */
public enum AsrErrorKind {

    /** VAD 模型文件缺失（V2 才会真正抛）。 */
    MODEL_MISSING("ASR_MODEL_MISSING", "VAD 模型未找到"),
    /** VAD 引擎加载失败。 */
    ENGINE_LOAD_FAILED("ASR_ENGINE_LOAD_FAILED", "VAD 引擎加载失败"),
    /** 请求的 provider id 不在注册表里。 */
    PROVIDER_NOT_FOUND("ASR_PROVIDER_NOT_FOUND", "ASR provider 未找到"),
    /** provider 返回了错误（HTTP 非 2xx、协议不符、网络失败）。 */
    PROVIDER_FAILED("ASR_PROVIDER_FAILED", "provider API 错误"),
    /** provider 调用超时。 */
    PROVIDER_TIMEOUT("ASR_PROVIDER_TIMEOUT", "provider 请求超时"),
    /** 缺 API Key 等凭据。 */
    MISSING_CREDENTIALS("ASR_MISSING_CREDENTIALS", "缺少凭据"),
    /** 音频格式不合法（不是 16kHz 单声道 WAV 等）。 */
    INVALID_AUDIO("ASR_INVALID_AUDIO", "音频格式无效"),
    /** 已有进行中的会话。 */
    SESSION_BUSY("ASR_SESSION_BUSY", "ASR 会话忙"),
    /** 会话被取消。 */
    CANCELED("ASR_CANCELED", "ASR 已取消"),
    /** 浏览器/系统拒绝了麦克风权限。 */
    MIC_DENIED("ASR_MIC_DENIED", "麦克风权限被拒绝"),
    /** 该 provider 不支持流式识别。 */
    STREAMING_UNSUPPORTED("ASR_STREAMING_UNSUPPORTED", "流式识别不受支持"),
    /**
     * 设置/参数校验不通过（越界值、非法的 source 等）。
     *
     * <p><b>这一项是本项目扩展，上游没有对应错误码</b>：上游 {@code asr_set_settings}
     * 不做任何校验——Rust 端 serde 反序列化成功后直接存盘，所以 {@code error.rs} 里
     * 没有「设置不合法」这个变体。我们在 {@link AsrSettingsService#validate} 里加了
     * 范围校验（越界的 {@code vad_silence_ms} 会让 VAD 静音判定彻底失真），既然多出了
     * 这个失败分支，就必须给它一个前端能识别、能查 i18n 的 code，否则前端只能显示
     * "HTTP 400"。
     */
    INVALID_SETTINGS("ASR_INVALID_SETTINGS", "语音设置不合法");

    private final String code;
    private final String defaultMessage;

    AsrErrorKind(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** 前端 i18n 查找键（{@code ASR_xxx}）。 */
    public String code() {
        return code;
    }

    /** 中文兜底文案，取自上游 {@code #[error(...)]} 的模板主体。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}

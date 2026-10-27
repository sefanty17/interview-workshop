package cn.edu.zjut.interview.speech.tts;

import org.springframework.http.HttpStatus;

/**
 * TTS 领域异常：带「错误分类 + HTTP 状态」两件事，由
 * {@link cn.edu.zjut.interview.api.SpeechExceptionHandler} 统一转成
 * {@code {code, detail, status}} 响应体。
 *
 * <p>形状刻意与 {@code AsrException} 一致（kind() / status() 两个访问器）：
 * 两类异常共用一个 Advice，处理器分支只要换个类型名，不必再造第二套响应结构。
 *
 * <p>status 不固化进 {@link TtsErrorKind}：同一个分类在不同入口未必同码
 * （请求体里写错 {@code ttsType} 是 400，而调用路径上遇到未接入的引擎更像 501），
 * 状态码属于「这一次调用」的判断。
 */
public class TtsException extends RuntimeException {

    private final TtsErrorKind kind;
    private final HttpStatus status;

    public TtsException(TtsErrorKind kind, HttpStatus status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public TtsException(TtsErrorKind kind, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public TtsErrorKind kind() {
        return kind;
    }

    public HttpStatus status() {
        return status;
    }

    /** 配置缺失属于请求方问题（400）：用户填的表单不全，不是服务器坏了。 */
    public static TtsException notConfigured(String message) {
        return new TtsException(TtsErrorKind.NOT_CONFIGURED, HttpStatus.BAD_REQUEST, message);
    }

    /** 引擎未接入：请求体里写了未知引擎用 400，见 {@code TtsSettingsService#validate}。 */
    public static TtsException unsupported(String message) {
        return new TtsException(TtsErrorKind.ENGINE_UNSUPPORTED, HttpStatus.BAD_REQUEST, message);
    }

    /** 上游非 2xx / 网络错误：这是「依赖的服务坏了」，502 而非 500——本服务本身没出错。 */
    public static TtsException requestFailed(String message) {
        return new TtsException(TtsErrorKind.REQUEST_FAILED, HttpStatus.BAD_GATEWAY, message);
    }

    /** 超时：网关等不到上游，仍是 504。 */
    public static TtsException timeout(String message) {
        return new TtsException(TtsErrorKind.TIMEOUT, HttpStatus.GATEWAY_TIMEOUT, message);
    }

    public static TtsException badResponse(String message) {
        return new TtsException(TtsErrorKind.BAD_RESPONSE, HttpStatus.BAD_GATEWAY, message);
    }
}

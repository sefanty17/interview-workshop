package cn.edu.zjut.interview.speech.asr;

import org.springframework.http.HttpStatus;

/**
 * ASR 领域异常：带「错误分类 + HTTP 状态」两件事，交给
 * {@link cn.edu.zjut.interview.api.SpeechExceptionHandler} 统一转成响应体。
 *
 * <p>为什么要自带 status 而不是让上层再 switch 一次 {@link AsrErrorKind}：
 * 同一个分类在不同入口的状态码未必相同（例如请求体里的 provider id 不存在是
 * 请求方的错，400；路径/配置里引用不存在的 provider 才是 404），
 * 状态码属于「这一次调用」的判断，不该固化进枚举。
 */
public class AsrException extends RuntimeException {

    private final AsrErrorKind kind;
    private final HttpStatus status;

    public AsrException(AsrErrorKind kind, HttpStatus status, String message) {
        super(message);
        this.kind = kind;
        this.status = status;
    }

    public AsrException(AsrErrorKind kind, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public AsrErrorKind kind() {
        return kind;
    }

    public HttpStatus status() {
        return status;
    }

    /** 缺凭据、音频非法属于请求方问题。 */
    public static AsrException missingCredentials(String message) {
        return new AsrException(AsrErrorKind.MISSING_CREDENTIALS, HttpStatus.BAD_REQUEST, message);
    }

    /** provider 不在注册表（作为资源不存在时用）。 */
    public static AsrException providerNotFound(String providerId) {
        return new AsrException(AsrErrorKind.PROVIDER_NOT_FOUND, HttpStatus.NOT_FOUND,
                "未知的语音识别 provider：" + providerId);
    }

    /** 已有会话在跑。 */
    public static AsrException sessionBusy() {
        return new AsrException(AsrErrorKind.SESSION_BUSY, HttpStatus.CONFLICT,
                "已有进行中的语音识别会话，请先结束当前会话");
    }
}

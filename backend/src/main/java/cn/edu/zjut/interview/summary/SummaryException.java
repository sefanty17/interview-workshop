package cn.edu.zjut.interview.summary;

import org.springframework.http.HttpStatus;

/**
 * 对话总结链路的领域异常（<b>本项目扩展（issue #4）</b>）。
 *
 * <p>与 {@code EvaluationException} 同一思路：带稳定 {@code code}，前端按 code 给文案，
 * 不靠解析中文消息。总结失败<b>不能阻断结束面试</b>——结束流程已经写入数据库，
 * 这里只是事后生成的附加产物，失败只影响报告页那一段。
 */
public class SummaryException extends RuntimeException {

    /** 本场没有求职者发言，没有可总结的内容。 */
    public static final String NO_ENOUGH_DIALOGUE = "SUMMARY_NO_ENOUGH_DIALOGUE";
    /** 模型输出里找不到可解析的 JSON。 */
    public static final String BAD_JSON = "SUMMARY_BAD_JSON";
    /** 调大模型失败（网络 / 超时 / 上游非 200）。 */
    public static final String LLM_FAILED = "SUMMARY_LLM_FAILED";
    /** 同一场次已有总结正在生成（重复点击时的并发保护）。 */
    public static final String IN_PROGRESS = "SUMMARY_IN_PROGRESS";
    /** 自由对话场次没有剧本流程，不生成总结（issue #4 只对剧本模式开放）。 */
    public static final String NOT_SCRIPTED = "SUMMARY_NOT_SCRIPTED";

    private final String code;
    private final HttpStatus status;

    public SummaryException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public SummaryException(String code, String message, Throwable cause) {
        this(code, message, HttpStatus.BAD_REQUEST, cause);
    }

    public SummaryException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public SummaryException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}

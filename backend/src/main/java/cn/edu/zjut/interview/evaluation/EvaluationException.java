package cn.edu.zjut.interview.evaluation;

import org.springframework.http.HttpStatus;

/**
 * 评估链路的领域异常（<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>与 {@code TtsException} 同思路：带一个稳定的 {@code code}，前端按 code 给不同文案
 * （「再多回答几轮」/「模型这次没返回结构化结果，可以重试」），
 * 而不是靠解析人话消息——文案改了不影响前端逻辑。
 *
 * <p>为什么必须显式抛而不是静默给 0 分：假的 0 分会被当成真实评估写进报告，
 * 学生看到「技术正确性 0 分」只会以为是自己的问题。
 */
public class EvaluationException extends RuntimeException {

    /** 场次里的求职者回答不足 2 条，样本太少没法评。 */
    public static final String NO_ENOUGH_DIALOGUE = "EVAL_NO_ENOUGH_DIALOGUE";
    /** 模型返回的不是能解析的 JSON（含围栏、前后废话都剥掉之后仍失败）。 */
    public static final String BAD_JSON = "EVAL_BAD_JSON";
    /** JSON 合法但缺维度 / 维度分不是数字。 */
    public static final String MISSING_DIMENSION = "EVAL_MISSING_DIMENSION";
    /** 给了未知的维度 code（模型自己造维度时用）。 */
    public static final String BAD_DIMENSION = "EVAL_BAD_DIMENSION";
    /** 调大模型失败（网络 / 超时 / 上游非 200）。 */
    public static final String LLM_FAILED = "EVAL_LLM_FAILED";

    private final String code;
    private final HttpStatus status;

    /** 默认 400：绝大多数情况（对话不足 / 维度缺失）都是这一次的输入或模型输出不合格。 */
    public EvaluationException(String code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public EvaluationException(String code, String message, Throwable cause) {
        this(code, message, HttpStatus.BAD_REQUEST, cause);
    }

    /** 显式给状态码：上游大模型挂了是 502（依赖的服务坏了），不是本服务 500。 */
    public EvaluationException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public EvaluationException(String code, String message, HttpStatus status, Throwable cause) {
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

package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.EvaluationResult;
import cn.edu.zjut.interview.evaluation.EvaluationException;
import cn.edu.zjut.interview.evaluation.EvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多维内容评估接口（<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <pre>
 * POST /api/sessions/{id}/evaluate    触发 / 重跑评估，返回完整评估结果
 * GET  /api/sessions/{id}/evaluation  取已有评估；没有 → 404
 * </pre>
 *
 * <p>为什么挂在独立的 {@code /evaluate} 而不是 {@code /finish} 上：一次评估要调一次完整的大模型请求
 * （十几秒），「结束面试」卡在这上面会让前端转圈到超时；拆开之后报告页可以在没有评估时自己触发，
 * 失败了也能单独重试，不影响场次已经结束这个事实。{@code InterviewEngine.finishNow} 因此一行都没改。
 *
 * <p>LingChat 没有这个功能，两条路径与两张表都是本项目新增。
 */
@RestController
@RequestMapping("/api/sessions")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /** 触发 / 重跑评估。重跑是覆盖（先删后插），一场最终只有一份评估。 */
    @PostMapping("/{id}/evaluate")
    public EvaluationResult evaluate(@PathVariable Long id) {
        return evaluationService.evaluate(id);
    }

    /** 取已有评估；没评估过返回 404（不在这里现算，见类注释）。 */
    @GetMapping("/{id}/evaluation")
    public EvaluationResult evaluation(@PathVariable Long id) {
        return evaluationService.load(id);
    }

    /**
     * {@link EvaluationException} → 带 code 的错误体，形状照 {@link SpeechExceptionHandler}
     * （{@code code} + {@code detail} + {@code status}）。
     *
     * <p>写在本控制器里而不是加进 {@code SpeechExceptionHandler}：那个 Advice 是语音线专用的
     * （只接 ASR / TTS 两类异常），评估的失败码与它无关，混进去会让两条线的错误码互相污染。
     *
     * <p>状态码取自异常本身：对话不足 / 维度缺失是 400，大模型调用失败是 502。
     */
    @ExceptionHandler(EvaluationException.class)
    public ResponseEntity<Map<String, Object>> handleEvaluation(EvaluationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("detail", e.getMessage());
        body.put("status", e.status().value());
        return ResponseEntity.status(e.status()).body(body);
    }
}

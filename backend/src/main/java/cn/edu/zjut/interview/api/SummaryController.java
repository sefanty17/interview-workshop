package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.SummaryResult;
import cn.edu.zjut.interview.summary.SummaryException;
import cn.edu.zjut.interview.summary.SummaryService;
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
 * 对话总结接口（<b>本项目扩展（issue #4）</b>）。
 *
 * <pre>
 * GET  /api/sessions/{id}/summary  取已有总结；没生成过 → 404，生成失败 → 200 + status=failed
 * POST /api/sessions/{id}/summary  重新生成（仅剧本场次；失败不改变场次状态）
 * </pre>
 *
 * <p>生成入口有两个：结束面试时后台自动触发（见 {@code SessionController#finish}），
 * 报告页发现「没生成过」或「生成失败」时再手动 POST 重试。
 * 两条路径都不影响 {@code /finish} 的结果——总结失败时场次照样是已结束。
 */
@RestController
@RequestMapping("/api/sessions")
public class SummaryController {

    private final SummaryService summaryService;

    public SummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    /** 重新生成（覆盖旧记录）。仅剧本场次，自由对话返回 400。 */
    @PostMapping("/{id}/summary")
    public SummaryResult generate(@PathVariable Long id) {
        return summaryService.generate(id);
    }

    /** 取已有总结；没生成过 404，失败记录 200（带 error 文案）。 */
    @GetMapping("/{id}/summary")
    public SummaryResult summary(@PathVariable Long id) {
        return summaryService.load(id);
    }

    /**
     * {@link SummaryException} → 带 code 的错误体，形状与 {@code EvaluationController} 一致。
     *
     * <p>本控制器只挂这一条 Advice：总结的失败码（对话不足 / 输出坏了 / 正在生成）
     * 与评估、语音两条线互不相干，混进公共 Advice 会让错误码互相污染。
     */
    @ExceptionHandler(SummaryException.class)
    public ResponseEntity<Map<String, Object>> handleSummary(SummaryException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("detail", e.getMessage());
        body.put("status", e.status().value());
        return ResponseEntity.status(e.status()).body(body);
    }
}

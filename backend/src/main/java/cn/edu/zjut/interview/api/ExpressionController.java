package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.ExpressionReport;
import cn.edu.zjut.interview.expression.ExpressionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表达分析接口（<b>本项目扩展（课设指标 ④）· 文本版</b>）。
 *
 * <pre>
 * GET /api/sessions/{id}/expression   → ExpressionReport
 * </pre>
 *
 * <p>路径挂在场次下而不是 {@code /api/reports/...}：它分析的是<b>这一场</b>的台词，
 * 与 {@code /messages}、{@code /{id}/evaluation} 是同一层级的概念；成长曲线那种跨场次汇总
 * 才归 {@code ReportController}。
 *
 * <p>刻意做成纯读接口、由报告页直接调用：算分不调大模型，耗时与一次查表相当，
 * 不需要像评估那样「先 GET 再 POST 生成」两段式。
 */
@RestController
@RequestMapping("/api/sessions")
public class ExpressionController {

    private final ExpressionService expressionService;

    public ExpressionController(ExpressionService expressionService) {
        this.expressionService = expressionService;
    }

    /** 场次不存在 404；自由对话 / 作答不足 2 次 400（前端据此隐藏该板块）。 */
    @GetMapping("/{id}/expression")
    public ExpressionReport expression(@PathVariable Long id) {
        return expressionService.analyze(id);
    }
}

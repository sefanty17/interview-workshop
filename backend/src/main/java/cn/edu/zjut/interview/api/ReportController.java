package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.TrendView;
import cn.edu.zjut.interview.report.TrendService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告与成长数据接口（<b>本项目扩展（课设指标 ⑥）</b>）。
 *
 * <pre>
 * GET /api/reports/trend?userId=   剧本场次的分维度趋势 + 短板推荐
 * </pre>
 *
 * <p>挂在独立的 {@code /api/reports} 而不是 {@code /api/sessions} 下面：它回答的是
 * 「多场之间的变化」，不是某一场的状态。{@code userId} 可省略（本项目当前单用户）。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final TrendService trendService;

    public ReportController(TrendService trendService) {
        this.trendService = trendService;
    }

    @GetMapping("/trend")
    public TrendView trend(@RequestParam(name = "userId", required = false) Long userId) {
        return trendService.trend(userId);
    }
}

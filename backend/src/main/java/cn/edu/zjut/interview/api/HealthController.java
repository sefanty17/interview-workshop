package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：确认「建表 + 种子数据 + JPA 映射 + 数据源」整条链路是通的。
 *
 * <p>数字全部来自 repository.count()，真查库，不是常量。
 * 期望（种子数据）：interviewer=4、interview_script=4、interview_chapter=21。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final InterviewerRepository interviewerRepository;
    private final InterviewScriptRepository interviewScriptRepository;
    private final InterviewChapterRepository interviewChapterRepository;

    public HealthController(InterviewerRepository interviewerRepository,
                            InterviewScriptRepository interviewScriptRepository,
                            InterviewChapterRepository interviewChapterRepository) {
        this.interviewerRepository = interviewerRepository;
        this.interviewScriptRepository = interviewScriptRepository;
        this.interviewChapterRepository = interviewChapterRepository;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> tables = new LinkedHashMap<>();
        tables.put("interviewer", interviewerRepository.count());
        tables.put("interview_script", interviewScriptRepository.count());
        tables.put("interview_chapter", interviewChapterRepository.count());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("tables", tables);
        return body;
    }
}

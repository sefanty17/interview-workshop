package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.InterviewerDetail;
import cn.edu.zjut.interview.dto.InterviewerSummary;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试官（角色卡）只读接口（规格 B6 §1）。
 *
 * <p>列表只吐卡面字段；详情才带完整 {@code personaPrompt}。
 * 找不到一律 404，返回体 {@code {"error":"not_found","code":"..."}}。
 */
@RestController
@RequestMapping("/api/interviewers")
public class InterviewerController {

    private final InterviewerRepository interviewerRepository;

    public InterviewerController(InterviewerRepository interviewerRepository) {
        this.interviewerRepository = interviewerRepository;
    }

    @GetMapping
    public List<InterviewerSummary> list() {
        return interviewerRepository.findAllByOrderBySortOrderAsc().stream()
                .map(InterviewerSummary::from)
                .toList();
    }

    @GetMapping("/{code}")
    public ResponseEntity<Object> detail(@PathVariable String code) {
        Interviewer interviewer = interviewerRepository.findByCode(code).orElse(null);
        if (interviewer == null) {
            return notFound(code);
        }
        return ResponseEntity.ok(InterviewerDetail.from(interviewer));
    }

    private static ResponseEntity<Object> notFound(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not_found");
        body.put("code", code);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}

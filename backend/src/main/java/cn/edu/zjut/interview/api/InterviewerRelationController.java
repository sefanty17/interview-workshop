package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.repository.InterviewerRepository;
import cn.edu.zjut.interview.service.InterviewerRelationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 角色对各方的出厂关系（对齐 {@code characters/<folder>/relations.yml} 的编辑器读写）。
 *
 * <p>GET 返回 {@code Map<String,String>}（键 {@code me:<身份id>} / {@code ai:<角色code>}），
 * PUT 是<b>整份覆盖</b>。
 */
@RestController
@RequestMapping("/api/interviewers")
public class InterviewerRelationController {

    private final InterviewerRelationService service;
    private final InterviewerRepository interviewerRepository;

    public InterviewerRelationController(InterviewerRelationService service,
                                         InterviewerRepository interviewerRepository) {
        this.service = service;
        this.interviewerRepository = interviewerRepository;
    }

    @GetMapping("/{code}/relations")
    public ResponseEntity<Object> get(@PathVariable String code) {
        if (interviewerRepository.findByCode(code).isEmpty()) {
            return notFound(code);
        }
        return ResponseEntity.ok(service.load(code));
    }

    @PutMapping("/{code}/relations")
    public ResponseEntity<Object> put(@PathVariable String code,
                                      @RequestBody(required = false) Map<String, String> relations) {
        if (interviewerRepository.findByCode(code).isEmpty()) {
            return notFound(code);
        }
        service.save(code, relations == null ? Map.of() : relations);
        return ResponseEntity.ok(service.load(code));
    }

    private static ResponseEntity<Object> notFound(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not_found");
        body.put("code", code);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}

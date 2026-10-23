package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.ChapterView;
import cn.edu.zjut.interview.dto.ScriptDetail;
import cn.edu.zjut.interview.dto.ScriptSummary;
import cn.edu.zjut.interview.importer.ImportResult;
import cn.edu.zjut.interview.importer.ResourceMapping;
import cn.edu.zjut.interview.importer.ScriptYamlImporter;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 剧本接口：只读查询（规格 B6 §1）+ 导入（规格 B5 §2.6）。
 *
 * <p>列表按 {@code order_num} 升序、可选 {@code ?job=} 过滤；详情把章节一起带上，
 * 章节里的 {@code events} 保持库里原样的 JSON 字符串。
 */
@RestController
@RequestMapping("/api/scripts")
public class ScriptController {

    private static final Logger log = LoggerFactory.getLogger(ScriptController.class);

    private final InterviewScriptRepository scriptRepository;
    private final InterviewChapterRepository chapterRepository;
    private final InterviewerRepository interviewerRepository;
    private final ScriptYamlImporter importer;

    /** 剧本自带背景的落盘根目录（导入时由同一个属性决定），只读给设置页展示。 */
    private final Path assetRoot;

    public ScriptController(InterviewScriptRepository scriptRepository,
                            InterviewChapterRepository chapterRepository,
                            InterviewerRepository interviewerRepository,
                            ScriptYamlImporter importer,
                            @Value("${app.import.asset-target:../frontend/public/backgrounds/scripts}")
                            String assetTarget) {
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.interviewerRepository = interviewerRepository;
        this.importer = importer;
        this.assetRoot = Path.of(assetTarget).toAbsolutePath().normalize();
    }

    @GetMapping
    public List<ScriptSummary> list(@RequestParam(name = "job", required = false) String job) {
        List<InterviewScript> scripts = (job == null || job.isBlank())
                ? scriptRepository.findAllByOrderByOrderNumAsc()
                : scriptRepository.findAllByJobCodeOrderByOrderNumAsc(job);
        return scripts.stream()
                .map(s -> ScriptSummary.from(s, interviewerCode(s.getInterviewerId())))
                .toList();
    }

    @GetMapping("/{code}")
    public ResponseEntity<Object> detail(@PathVariable String code) {
        InterviewScript script = scriptRepository.findByCode(code).orElse(null);
        if (script == null) {
            return notFound(code);
        }
        List<ChapterView> chapters = chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId()).stream()
                .map(ChapterView::from)
                .toList();
        return ResponseEntity.ok(ScriptDetail.from(script, interviewerCode(script.getInterviewerId()), chapters));
    }

    /**
     * 导入剧本 zip（规格 B5 §2.6）。
     *
     * <p>返回的是 {@link ImportResult}——报告和落库同时发生：绿项照跑，黄/红项原样留在库里，
     * 由作者后续在编辑器里补。{@code scriptCode} 可选，不给就自动分配 {@code imported-N}。
     */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<Object> importZip(@RequestParam("file") MultipartFile file,
                                            @RequestParam(name = "scriptCode", required = false) String scriptCode) {
        if (file == null || file.isEmpty()) {
            return badRequest("empty_file", "上传的文件是空的");
        }
        try {
            return ResponseEntity.ok(importer.importScript(file.getBytes(), scriptCode));
        } catch (ScriptYamlImporter.InvalidScriptException e) {
            return badRequest("invalid_script", e.getMessage());
        } catch (IOException e) {
            return badRequest("read_failed", "读取上传内容失败：" + e.getMessage());
        }
    }

    /**
     * 导入本机目录（开发自测用，规格 B5 §2.6）。
     *
     * <p>请求体 {@code {"path": "..."}}，相对路径按后端进程工作目录解析。
     */
    @PostMapping("/import/local")
    public ResponseEntity<Object> importLocal(@RequestBody Map<String, String> body) {
        String raw = body == null ? null : body.get("path");
        if (raw == null || raw.isBlank()) {
            return badRequest("missing_path", "请求体需要 path 字段");
        }
        Path dir;
        try {
            dir = Path.of(raw.trim());
        } catch (InvalidPathException e) {
            return badRequest("invalid_path", "路径不合法：" + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return badRequest("path_not_found", "目录不存在：" + dir.toAbsolutePath());
        }
        try {
            return ResponseEntity.ok(importer.importDirectory(dir));
        } catch (ScriptYamlImporter.InvalidScriptException e) {
            log.warn("[ScriptController] 导入失败 {}：{}", dir, e.getMessage());
            return badRequest("invalid_script", e.getMessage());
        }
    }

    /**
     * 剧本自带背景清单（导入时复制到 {@code frontend/public/backgrounds/scripts/<code>/}）。
     *
     * <p>只读：给设置页确认导入的图确实能被前端加载，不做任何清理或写入。
     */
    @GetMapping("/background-assets")
    public List<Map<String, String>> backgroundAssets() {
        if (!Files.isDirectory(assetRoot)) {
            return List.of();
        }
        List<Path> scriptDirs;
        try (Stream<Path> dirs = Files.list(assetRoot)) {
            scriptDirs = dirs.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("[ScriptController] 读取剧本背景目录失败 {}：{}", assetRoot, e.getMessage());
            return List.of();
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Path dir : scriptDirs) {
            String code = dir.getFileName().toString();
            String name = scriptRepository.findByCode(code).map(InterviewScript::getScriptName).orElse(code);
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> ResourceMapping.isImage(p.getFileName().toString()))
                        .sorted()
                        .forEach(p -> {
                            Map<String, String> row = new LinkedHashMap<>();
                            row.put("scriptCode", code);
                            row.put("scriptName", name);
                            row.put("file", p.getFileName().toString());
                            row.put("url", ResourceMapping.SCRIPT_ASSET_PREFIX + code + "/" + p.getFileName());
                            rows.add(row);
                        });
            } catch (IOException e) {
                log.warn("[ScriptController] 读取剧本背景失败 {}：{}", dir, e.getMessage());
            }
        }
        rows.sort((a, b) -> a.get("url").compareTo(b.get("url")));
        return rows;
    }

    /** interviewer_id → code；关联不上给 null，不因外键脏数据让接口 500。 */
    private String interviewerCode(Long interviewerId) {
        if (interviewerId == null) {
            return null;
        }
        return interviewerRepository.findById(interviewerId).map(Interviewer::getCode).orElse(null);
    }

    private static ResponseEntity<Object> notFound(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not_found");
        body.put("code", code);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    private static ResponseEntity<Object> badRequest(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }
}

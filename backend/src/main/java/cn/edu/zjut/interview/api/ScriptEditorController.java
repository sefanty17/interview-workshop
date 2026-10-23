package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.EditorAssetFileView;
import cn.edu.zjut.interview.dto.EditorChapterContent;
import cn.edu.zjut.interview.dto.EditorCreateChapterRequest;
import cn.edu.zjut.interview.dto.EditorCreateScriptRequest;
import cn.edu.zjut.interview.dto.EditorScriptDetail;
import cn.edu.zjut.interview.dto.EditorScriptPackage;
import cn.edu.zjut.interview.dto.EditorWriteChapterRequest;
import cn.edu.zjut.interview.dto.EditorWriteConfigRequest;
import cn.edu.zjut.interview.dto.PreviewSessionView;
import cn.edu.zjut.interview.dto.ValidationReportView;
import cn.edu.zjut.interview.dto.schema.ScriptSchemaView;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import cn.edu.zjut.interview.editor.EditorNotFoundException;
import cn.edu.zjut.interview.editor.ScriptSchemaCatalog;
import cn.edu.zjut.interview.service.PreviewService;
import cn.edu.zjut.interview.service.ScriptEditorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 剧本编辑器接口（规格 E1 + E1b + E5a），对应原版 26 个 {@code editor_*} 命令里的 11 个。
 *
 * <p>章节 id 走查询参数 {@code ?id=...} 而不是路径变量：原版的章节 id 可以带 {@code /}
 * （如 {@code Intro/main}），而 Spring 6 的路径变量默认不吞 {@code /}，
 * {@code {chapterId:.+}} 这类写法也不可靠。所以：
 * <pre>
 * GET    /api/editor/schema
 * GET    /api/editor/scripts
 * POST   /api/editor/scripts
 * GET    /api/editor/scripts/{key}
 * DELETE /api/editor/scripts/{key}
 * GET    /api/editor/scripts/{key}/validate
 * GET    /api/editor/scripts/{key}/chapter?id=&lt;chapterId&gt;
 * PUT    /api/editor/scripts/{key}/chapter?id=&lt;chapterId&gt;
 * DELETE /api/editor/scripts/{key}/chapter?id=&lt;chapterId&gt;
 * POST   /api/editor/scripts/{key}/chapter
 * PUT    /api/editor/scripts/{key}/config
 * POST   /api/editor/scripts/{key}/preview
 * DELETE /api/editor/preview/{sessionId}
 * </pre>
 */
@RestController
@RequestMapping("/api/editor")
public class ScriptEditorController {

    /** 起始章节 id 的合法字符集（规格 E5b §1.3）：与 {@code interview_chapter.code} 的写入规则同源。 */
    private static final Pattern CHAPTER_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ScriptEditorService service;
    private final PreviewService previewService;

    public ScriptEditorController(ScriptEditorService service, PreviewService previewService) {
        this.service = service;
        this.previewService = previewService;
    }

    @GetMapping("/schema")
    public ScriptSchemaView schema() {
        return ScriptSchemaCatalog.build();
    }

    @GetMapping("/scripts")
    public List<EditorScriptPackage> listScripts() {
        return service.listScripts();
    }

    @GetMapping("/scripts/{key}")
    public EditorScriptDetail readScript(@PathVariable String key) {
        return service.readScript(key);
    }

    /** 新建剧本（规格 E5a）：建一行 interview_script + 一个开场章节，返回新剧本的包信息。 */
    @PostMapping("/scripts")
    public ResponseEntity<EditorScriptPackage> createScript(@RequestBody(required = false)
                                                            EditorCreateScriptRequest body) {
        return ResponseEntity.ok(service.createScript(body));
    }

    /**
     * 删除整个剧本（规格 E5a）：先解绑引用它的场次，再删章节与剧本行。
     *
     * <p>返回 204 无 body，与删除章节一致。
     */
    @DeleteMapping("/scripts/{key}")
    public ResponseEntity<Void> deleteScript(@PathVariable String key) {
        service.deleteScript(key);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/scripts/{key}/chapter")
    public EditorChapterContent readChapter(@PathVariable String key, @RequestParam("id") String id) {
        return service.readChapter(key, id);
    }

    /** 剧本校验（规格 E1b）：诊断列表 + 三个计数 + 变量清单 + 章节跳转边。 */
    @GetMapping("/scripts/{key}/validate")
    public ValidationReportView validate(@PathVariable String key) {
        return service.validate(key);
    }

    @PutMapping("/scripts/{key}/chapter")
    public ResponseEntity<Void> writeChapter(@PathVariable String key,
                                             @RequestParam("id") String id,
                                             @RequestBody(required = false) EditorWriteChapterRequest body) {
        service.writeChapter(key, id, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/scripts/{key}/chapter")
    public ResponseEntity<EditorChapterContent> createChapter(@PathVariable String key,
                                                              @RequestBody(required = false)
                                                              EditorCreateChapterRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createChapter(key, body));
    }

    @DeleteMapping("/scripts/{key}/chapter")
    public ResponseEntity<Void> deleteChapter(@PathVariable String key, @RequestParam("id") String id) {
        service.deleteChapter(key, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/scripts/{key}/config")
    public ResponseEntity<Void> writeConfig(@PathVariable String key,
                                            @RequestBody(required = false) EditorWriteConfigRequest body) {
        service.writeConfig(key, body);
        return ResponseEntity.noContent().build();
    }

    /**
     * 开始试玩（规格 E5b §1.3，原版 {@code editor_start_preview}）。
     *
     * <p>建一个 {@code preview=true} 的专用场次并返回它的信息；前端拿到 {@code sessionId} 后
     * 走正式游玩那套 {@code /api/sessions/{id}/script/start} 推进，所以试玩与正式画面逐帧一致。
     *
     * @param body 可选；{@code fromChapter} 为空表示从 {@code intro_chapter} 开演
     */
    @PostMapping("/scripts/{key}/preview")
    public PreviewSessionView startPreview(@PathVariable String key,
                                           @RequestBody(required = false) PreviewRequest body) {
        String fromChapter = body == null ? null : body.fromChapter();
        validateFromChapter(fromChapter);
        return previewService.start(key, fromChapter);
    }

    /**
     * 停止试玩（规格 E5b §1.3，原版 {@code editor_stop_preview}）。
     *
     * <p>删掉试玩场次与它的台词；非试玩场次的 id 传进来是 no-op，不会误删真实记录。
     */
    @DeleteMapping("/preview/{sessionId}")
    public Map<String, Boolean> stopPreview(@PathVariable Long sessionId) {
        previewService.discard(sessionId);
        return Map.of("ok", true);
    }

    /** {@code POST /api/editor/scripts/{key}/preview} 的请求体：只带起始章节。 */
    public record PreviewRequest(String fromChapter) {
    }

    // ============================================================
    // 外观（规格 E6a §8）
    // ============================================================

    /** {@code POST /api/editor/appearance/background} 的请求体：dataURL + 目标文件名。 */
    public record AppearanceBackgroundRequest(String dataUrl, String name) {
    }

    /**
     * 上传裁剪后的编辑器背景。{@code name} 白名单 {@code ^[A-Za-z0-9_-]+\.webp$}，
     * {@code dataUrl} 必须以 {@code data:image/} 开头且 base64 可解码，否则 400。
     */
    @PostMapping("/appearance/background")
    public EditorAssetFileView uploadBackground(@RequestBody(required = false)
                                                AppearanceBackgroundRequest body) {
        if (body == null) {
            throw new EditorBadRequestException("请求体不能为空");
        }
        return service.uploadEditorBackground(body.dataUrl(), body.name());
    }

    /** 合并内置 / 剧本自带 / 编辑器上传三处的全局背景清单，按 url 排序。 */
    @GetMapping("/appearance/backgrounds")
    public List<EditorAssetFileView> listBackgrounds() {
        return service.listBackgrounds();
    }

    /** 起始章节 id 的格式校验：非法字符直接 400，不让它进 SQL / 引擎。 */
    private void validateFromChapter(String fromChapter) {
        if (fromChapter == null || fromChapter.isBlank()) {
            return;
        }
        if (!CHAPTER_CODE_PATTERN.matcher(fromChapter).matches()) {
            throw new EditorBadRequestException("章节 id 不合法：" + fromChapter);
        }
    }

    // ============================================================
    // 错误响应形状沿用 ScriptController：{error, message}
    // ============================================================

    @ExceptionHandler(EditorNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(EditorNotFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not_found");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(EditorBadRequestException.class)
    public ResponseEntity<Object> handleBadRequest(EditorBadRequestException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad_request");
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}

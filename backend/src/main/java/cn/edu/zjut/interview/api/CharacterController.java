package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.character.CharacterCard;
import cn.edu.zjut.interview.dto.character.CharacterCardSummary;
import cn.edu.zjut.interview.dto.character.SaveCharacterRequest;
import cn.edu.zjut.interview.editor.CharacterNotFoundException;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import cn.edu.zjut.interview.service.CharacterCardService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色卡接口（规格 R1a §5），对应原版「磁盘目录 + settings.yml」这一套的读写面：
 *
 * <pre>
 * GET    /api/editor/characters            全部角色卡摘要
 * GET    /api/editor/characters/{folder}   单张卡（含 rawYaml）
 * POST   /api/editor/characters/{folder}   新建（body 可选：初始字段）
 * PUT    /api/editor/characters/{folder}   保存（body：fields 或 rawYaml）
 * DELETE /api/editor/characters/{folder}   整目录删除
 * </pre>
 *
 * <p>{@code folder} 是目录名（中文，如 {@code 风雪}），不是 {@code interviewer.code}；
 * 两者的映射在 {@link CharacterCardService#FOLDER_TO_CODE}。
 * 目录名里的 {@code . / \} 一律 400，路径穿越在进服务层之前就被挡住。
 */
@RestController
@RequestMapping("/api/editor/characters")
public class CharacterController {

    private final CharacterCardService service;

    public CharacterController(CharacterCardService service) {
        this.service = service;
    }

    /** 全部角色卡摘要；坏卡在列表里被跳过，不会让整个接口 500。 */
    @GetMapping
    public List<CharacterCardSummary> listCharacters() {
        return service.listCharacters();
    }

    /** 单张卡详情（含整份 {@code rawYaml}）；目录不存在 → 404。 */
    @GetMapping("/{folder}")
    public CharacterCard getCharacter(@PathVariable String folder) {
        return service.getCharacter(folder);
    }

    /**
     * 新建一张卡：建目录 + 写 {@code settings.yml}，返回新建后的卡（201）。
     *
     * <p>body 可空（原版新建角色也是先给个空壳再编辑）；给了 body 就当初始字段写进去。
     */
    @PostMapping("/{folder}")
    public ResponseEntity<CharacterCard> createCharacter(@PathVariable String folder,
                                                         @RequestBody(required = false)
                                                         SaveCharacterRequest body) {
        service.createCharacter(folder, body == null ? null : body.fields());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.getCharacter(folder));
    }

    /**
     * 保存一张卡（204）：{@code rawYaml} 与 {@code fields} 二选一，两个都给以 {@code rawYaml} 为准。
     */
    @PutMapping("/{folder}")
    public ResponseEntity<Void> saveCharacter(@PathVariable String folder,
                                              @RequestBody(required = false) SaveCharacterRequest body) {
        if (body == null) {
            throw new EditorBadRequestException("请求体不能为空（需要 rawYaml 或 fields）");
        }
        if (body.rawYaml() != null && !body.rawYaml().isBlank()) {
            service.saveCharacterRaw(folder, body.rawYaml());
        } else if (body.fields() != null && !body.fields().isEmpty()) {
            service.saveCharacter(folder, body.fields());
        } else {
            throw new EditorBadRequestException("rawYaml 与 fields 至少要给一个");
        }
        return ResponseEntity.noContent().build();
    }

    /** 整目录删除（204）；目录不存在 → 404。 */
    @DeleteMapping("/{folder}")
    public ResponseEntity<Void> deleteCharacter(@PathVariable String folder) {
        service.deleteCharacter(folder);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // 错误响应形状沿用 ScriptEditorController：{error, message}
    // ============================================================

    @ExceptionHandler(CharacterNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(CharacterNotFoundException e) {
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

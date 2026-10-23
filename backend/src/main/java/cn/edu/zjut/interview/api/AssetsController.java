package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.EditorAssetFileView;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import cn.edu.zjut.interview.editor.EditorNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 素材接口（规格 E6b §3），对应原版 {@code editor_list_asset_files} /
 * {@code editor_upload_asset} / {@code editor_delete_asset} 三个命令。
 *
 * <p>原版是 Tauri 直接读写 {@code game_data/}；本项目的运行时素材都在
 * {@code frontend/public/} 下（Vite dev 直接伺服，构建后进 dist），所以：
 * <ul>
 *   <li>「全局」落点对齐本项目的实际目录：背景 {@code backgrounds/}、插图 {@code pictures/animation/}、
 *       音乐 {@code musics/}、音效 {@code audio_effects/}、环境音 {@code ambients/}；</li>
 *   <li>「本剧本」落点沿用 E6a 的剧本素材目录 {@code backgrounds/scripts/<key>/}，
 *       其余类别没有剧本级目录，列表恒为空、上传拒绝；</li>
 *   <li>立绘 {@code avatars/} 属于角色卡资产（角色 Tab 管），列出但不可删除、不可覆盖上传；</li>
 *   <li>内置三张背景（{@code ResourceMapping.BACKGROUNDS} 指到的顶层文件）引擎按文件名引用，
 *       列出但不可删除。</li>
 * </ul>
 *
 * <pre>
 * GET    /api/editor/assets/files?scope=global|script&amp;key=&lt;scriptKey&gt;
 * POST   /api/editor/assets/upload      body: {kind, scope, key, name, dataUrl}
 * DELETE /api/editor/assets/file?scope=&amp;key=&amp;kind=&amp;name=
 * </pre>
 *
 * <p>错误响应形状沿用 ScriptEditorController：{@code {error, message}}。
 */
@RestController
@RequestMapping("/api/editor/assets")
public class AssetsController {

    private static final Logger log = LoggerFactory.getLogger(AssetsController.class);

    /** 五类素材，键名与原版 AssetKind 一致。 */
    private static final Set<String> KINDS = Set.of("background", "pic", "music", "sound", "ambient");

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "bmp", "gif");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "ogg", "flac");

    /** 上传文件名白名单：中英文、数字、下划线、点、短横线；不含路径分隔符与「..」。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[^/\\\\]+\\.\\w+$");

    private final Path publicRoot;

    public AssetsController(
            @Value("${app.import.asset-target:../frontend/public/backgrounds/scripts}")
            String assetTarget) {
        // 与 ScriptEditorService 同一条解析逻辑：assetTarget 向上两级即 frontend/public
        this.publicRoot = Path.of(assetTarget).toAbsolutePath().normalize().getParent().getParent();
    }

    // ============================================================
    // 读
    // ============================================================

    /** 五类素材的文件清单；一类一条列表，按文件名排序。 */
    public record AssetFileIndex(List<EditorAssetFileView> background,
                                 List<EditorAssetFileView> pic,
                                 List<EditorAssetFileView> music,
                                 List<EditorAssetFileView> sound,
                                 List<EditorAssetFileView> ambient) {
    }

    @GetMapping("/files")
    public AssetFileIndex listFiles(@RequestParam("scope") String scope,
                                    @RequestParam(value = "key", required = false) String key) {
        requireScope(scope, key);
        return new AssetFileIndex(
                listKind("background", scope, key),
                listKind("pic", scope, key),
                listKind("music", scope, key),
                listKind("sound", scope, key),
                listKind("ambient", scope, key));
    }

    // ============================================================
    // 写
    // ============================================================

    /** {@code POST /api/editor/assets/upload} 的请求体：dataURL + 目标文件名。 */
    public record UploadRequest(String kind, String scope, String key, String name, String dataUrl) {
    }

    @PostMapping("/upload")
    public EditorAssetFileView upload(@RequestBody(required = false) UploadRequest body) {
        if (body == null) {
            throw new EditorBadRequestException("请求体不能为空");
        }
        String kind = requireKind(body.kind());
        requireScope(body.scope(), body.key());
        String name = requireName(kind, body.name());
        byte[] bytes = decodeDataUrl(kind, body.dataUrl());

        Path target = uploadDir(kind, body.scope(), body.key()).resolve(name);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new EditorBadRequestException("素材写入失败：" + e.getMessage());
        }
        log.info("[AssetsController] 素材已落盘 {}（{} 字节）", target, bytes.length);
        return new EditorAssetFileView(name, urlOf(target), bytes.length);
    }

    @DeleteMapping("/file")
    public Map<String, Boolean> delete(@RequestParam("scope") String scope,
                                       @RequestParam(value = "key", required = false) String key,
                                       @RequestParam("kind") String kind,
                                       @RequestParam("name") String name) {
        requireScope(scope, key);
        // 剧本级素材只有背景图（backgrounds/scripts/<key>/）
        Path deletableRoot = switch (scope) {
            case "script" -> publicRoot.resolve("backgrounds").resolve("scripts").resolve(key);
            default -> deletableGlobalRoot(kind);
        };
        if (deletableRoot == null) {
            throw new EditorBadRequestException(deletableDeniedReason(kind));
        }

        Path target = deletableRoot.resolve(name).normalize();
        // 双保险：解析后必须仍落在可删目录内，防止「..」之类的路径逃逸
        if (!target.normalize().startsWith(deletableRoot.normalize())) {
            throw new EditorBadRequestException("文件名不合法：" + name);
        }
        if (!Files.isRegularFile(target)) {
            throw new EditorNotFoundException("找不到素材「" + name + "」");
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw new EditorBadRequestException("删除素材失败：" + e.getMessage());
        }
        log.info("[AssetsController] 素材已删除 {}", target);
        return Map.of("ok", true);
    }

    // ============================================================
    // 内部
    // ============================================================

    /** 一类素材的一个 scope 的清单；目录缺失整段返回空，不报错（与原版一致）。 */
    private List<EditorAssetFileView> listKind(String kind, String scope, String key) {
        List<Path> dirs;
        if ("script".equals(scope)) {
            // 本项目剧本级素材只有背景图：backgrounds/scripts/<key>/**
            dirs = "background".equals(kind)
                    ? List.of(publicRoot.resolve("backgrounds").resolve("scripts").resolve(key))
                    : List.of();
        } else {
            dirs = globalDirs(kind);
        }
        Set<String> allowed = allowedExtensions(kind);
        List<EditorAssetFileView> out = new ArrayList<>();
        for (Path dir : dirs) {
            collectFiles(dir, dir, allowed, out);
        }
        out.sort(Comparator.comparing(EditorAssetFileView::name));
        return out;
    }

    /** 全局素材的搜索目录（含递归根）。 */
    private List<Path> globalDirs(String kind) {
        return switch (kind) {
            // 背景 = 内置顶层 + 编辑器上传 + 各剧本自带（剧本自带的 url 带 scripts/<key> 前缀）
            case "background" -> List.of(
                    publicRoot.resolve("backgrounds"),
                    publicRoot.resolve("backgrounds").resolve("editor"),
                    publicRoot.resolve("backgrounds").resolve("scripts"));
            // 插图 = 气泡动画（pictures/animation）+ 立绘（avatars，角色卡资产，只读）
            case "pic" -> List.of(
                    publicRoot.resolve("pictures").resolve("animation"),
                    publicRoot.resolve("avatars"));
            case "music" -> List.of(publicRoot.resolve("musics"));
            case "sound" -> List.of(publicRoot.resolve("audio_effects"));
            case "ambient" -> List.of(publicRoot.resolve("ambients"));
            default -> List.of();
        };
    }

    /** 递归收集 dir 下的素材文件；base 用于把绝对路径折算成站点内 url。 */
    private void collectFiles(Path base, Path dir, Set<String> allowed,
                              List<EditorAssetFileView> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                if (Files.isDirectory(p)) {
                    collectFiles(base, p, allowed, out);
                    continue;
                }
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (name.startsWith(".") || !allowed.contains(extension(name))) {
                    continue;
                }
                long size;
                try {
                    size = Files.size(p);
                } catch (IOException ignored) {
                    continue;
                }
                out.add(new EditorAssetFileView(name, urlOf(p), size));
            }
        } catch (IOException ignored) {
            // 目录不可读整段跳过，与 E6a 的清单合并逻辑一致
        }
    }

    /** 上传的落点目录。 */
    private Path uploadDir(String kind, String scope, String key) {
        if ("script".equals(scope)) {
            if (!"background".equals(kind)) {
                throw new EditorBadRequestException(
                        "本项目剧本级素材只支持背景图，" + kindLabel(kind) + "请导入全局");
            }
            return publicRoot.resolve("backgrounds").resolve("scripts").resolve(key);
        }
        return switch (kind) {
            // 编辑器上传的背景统一进 backgrounds/editor（对齐 E6a），不碰内置三张
            case "background" -> publicRoot.resolve("backgrounds").resolve("editor");
            case "pic" -> publicRoot.resolve("pictures").resolve("animation");
            case "music" -> publicRoot.resolve("musics");
            case "sound" -> publicRoot.resolve("audio_effects");
            case "ambient" -> publicRoot.resolve("ambients");
            default -> throw new EditorBadRequestException("未知的素材类别：" + kind);
        };
    }

    /** 全局删除的允许目录；null = 该类没有可删目录（附理由，见 {@link #deletableDeniedReason}）。 */
    private Path deletableGlobalRoot(String kind) {
        return switch (kind) {
            case "background" -> publicRoot.resolve("backgrounds").resolve("editor");
            case "pic" -> publicRoot.resolve("pictures").resolve("animation");
            case "music" -> publicRoot.resolve("musics");
            case "sound" -> publicRoot.resolve("audio_effects");
            case "ambient" -> publicRoot.resolve("ambients");
            default -> null;
        };
    }

    private String deletableDeniedReason(String kind) {
        return switch (kind) {
            // 立绘是角色卡资产，删了角色 Tab / 面试间就缺图
            case "pic" -> "立绘属于角色卡资产，请在角色卡目录内管理，不支持在这里删除";
            case "background" -> "内置背景被引擎按文件名引用，不支持删除（编辑器上传的背景可以删）";
            default -> "未知的素材类别：" + kind;
        };
    }

    private void requireScope(String scope, String key) {
        if (!"global".equals(scope) && !"script".equals(scope)) {
            throw new EditorBadRequestException("scope 只支持 global / script：" + scope);
        }
        if ("script".equals(scope) && (key == null || key.isBlank())) {
            throw new EditorBadRequestException("scope=script 时必须带剧本 key");
        }
    }

    private String requireKind(String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new EditorBadRequestException("未知的素材类别：" + kind);
        }
        return kind;
    }

    private String requireName(String kind, String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches() || name.contains("..")) {
            throw new EditorBadRequestException("文件名不合法：" + name);
        }
        if (!allowedExtensions(kind).contains(extension(name))) {
            throw new EditorBadRequestException(
                    kindLabel(kind) + "只支持 " + String.join(" / ", allowedExtensions(kind))
                            + " 扩展名：" + name);
        }
        return name;
    }

    private byte[] decodeDataUrl(String kind, String dataUrl) {
        String prefix = isImageKind(kind) ? "data:image/" : "data:audio/";
        if (dataUrl == null || !dataUrl.startsWith(prefix)) {
            throw new EditorBadRequestException("dataUrl 必须以 " + prefix + " 开头");
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new EditorBadRequestException("dataUrl 缺少 base64 数据段");
        }
        try {
            return Base64.getMimeDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new EditorBadRequestException("dataUrl 的 base64 数据段解码失败");
        }
    }

    private static boolean isImageKind(String kind) {
        return "background".equals(kind) || "pic".equals(kind);
    }

    private static Set<String> allowedExtensions(String kind) {
        return isImageKind(kind) ? IMAGE_EXTENSIONS : AUDIO_EXTENSIONS;
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "background" -> "背景图";
            case "pic" -> "插图";
            case "music" -> "背景音乐";
            case "sound" -> "音效";
            case "ambient" -> "环境音";
            default -> kind;
        };
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 绝对路径 → 站点内 url（frontend/public 即站点根）。 */
    private String urlOf(Path file) {
        Path relative = publicRoot.relativize(file.normalize());
        return "/" + relative.toString().replace('\\', '/');
    }

    // ============================================================
    // 错误响应形状沿用 ScriptEditorController：{error, message}
    // ============================================================

    @ExceptionHandler(EditorNotFoundException.class)
    public org.springframework.http.ResponseEntity<Object> handleNotFound(EditorNotFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not_found");
        body.put("message", e.getMessage());
        return org.springframework.http.ResponseEntity.status(
                org.springframework.http.HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(EditorBadRequestException.class)
    public org.springframework.http.ResponseEntity<Object> handleBadRequest(EditorBadRequestException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad_request");
        body.put("message", e.getMessage());
        return org.springframework.http.ResponseEntity.badRequest().body(body);
    }
}

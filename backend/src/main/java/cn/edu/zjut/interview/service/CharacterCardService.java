package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.character.CharacterCard;
import cn.edu.zjut.interview.dto.character.CharacterCardSummary;
import cn.edu.zjut.interview.editor.CharacterNotFoundException;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 角色卡的读写（规格 R1a），1:1 照搬 LingChat 的「目录即单位」模型：
 *
 * <pre>
 * game_data/characters/&lt;folder&gt;/settings.yml   ← 角色全字段
 * game_data/characters/&lt;folder&gt;/avatar/*.webp  ← 情绪立绘（本项目不搬，见 §2）
 * </pre>
 *
 * <p><b>与原版的差异（技术栈导致，不是功能缺失）</b>：
 * <table border="1">
 *   <tr><th>原版</th><th>本项目</th></tr>
 *   <tr><td>tauri 前端直读磁盘</td><td>后端读写磁盘 + REST 接口</td></tr>
 *   <tr><td>{@code avatar/} 情绪立绘</td><td>立绘在 {@code frontend/public/avatars/&lt;code&gt;/}，不搬家</td></tr>
 *   <tr><td>写 {@code settings.yml.bak} 备份</td><td>写 {@code .tmp} 后原子替换（本项目没有 .bak 约定）</td></tr>
 * </table>
 *
 * <p>目录名（folder）即角色标识，与 {@code interviewer.code} 的映射写死在
 * {@link #FOLDER_TO_CODE}：既有库里的 4 张卡就是这么对应的。
 */
@Service
public class CharacterCardService {

    private static final Logger log = LoggerFactory.getLogger(CharacterCardService.class);

    /** 目录名 → {@code interviewer.code}，与既有种子数据一一对应（规格 R1a §2）。 */
    public static final Map<String, String> FOLDER_TO_CODE = Map.of(
            "风雪", "strict",
            "诺一钦灵", "friendly",
            "松子", "pressure",
            "DeepSeek", "expert");

    /** 角色全字段的文件名，与原版一致。 */
    private static final String SETTINGS_FILE = "settings.yml";

    /** 目录名长度上限（规格 R1a §4.2）。 */
    private static final int MAX_FOLDER_LENGTH = 64;

    private final InterviewerRepository interviewerRepository;

    /** 角色卡根目录：{@code app.characters-dir} 解析后的绝对路径，一切路径操作都以它为前缀。 */
    private final Path root;

    public CharacterCardService(InterviewerRepository interviewerRepository,
                                @Value("${app.characters-dir}") String charactersDir) {
        this.interviewerRepository = interviewerRepository;
        this.root = resolveRoot(charactersDir);
        log.info("[CharacterCardService] 角色卡目录 = {}", root);
    }

    // ============================================================
    // 读
    // ============================================================

    /**
     * 列出所有角色卡：遍历根目录下每个子目录，读 {@code settings.yml} 抽摘要。
     *
     * <p>缺文件或解析失败的目录<b>跳过并 warn</b>，不抛异常 —— 一张坏卡不该让整个角色 Tab 打不开。
     * 根目录本身不存在时返回空 list。
     */
    public List<CharacterCardSummary> listCharacters() {
        List<Path> dirs = childDirectories();
        List<CharacterCardSummary> out = new ArrayList<>(dirs.size());
        for (Path dir : dirs) {
            String folder = dir.getFileName().toString();
            try {
                out.add(toSummary(folder, readSettings(dir)));
            } catch (Exception e) {
                log.warn("[CharacterCardService] 跳过角色卡 {}：{}", folder, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 读单张卡。目录不存在或没有 {@code settings.yml} → {@link CharacterNotFoundException}（404）；
     * 目录名不合法 → {@link EditorBadRequestException}（400）。
     */
    public CharacterCard getCharacter(String folder) {
        Path dir = resolveDir(folder);
        Map<String, Object> fields = readSettings(dir);
        return toCard(folder, fields, dump(fields));
    }

    // ============================================================
    // 写
    // ============================================================

    /**
     * 按字段覆盖一张卡：dump 成 yml → 写 {@code settings.yml.tmp} → 原子替换原文件。
     *
     * <p>目录不存在、目录名含 {@code . / \} → 400。
     */
    public void saveCharacter(String folder, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new EditorBadRequestException("fields 不能为空");
        }
        Path dir = resolveDir(folder);
        if (!Files.isDirectory(dir)) {
            throw new EditorBadRequestException("角色目录不存在: '" + folder + "'");
        }
        writeSettings(dir, fields);
        log.info("[CharacterCardService] 角色卡已保存 {}（{} 个字段）", folder, fields.size());
    }

    /**
     * 整份覆盖一张卡：前端直接编辑 {@code rawYaml} 文本框时的那一档（规格 R1a §4 的
     * {@code SaveCharacterRequest.rawYaml}）。先解析成 Map 确认它是合法 yml，再走
     * {@link #saveCharacter} 同一条落盘路径。
     */
    public void saveCharacterRaw(String folder, String rawYaml) {
        if (rawYaml == null || rawYaml.isBlank()) {
            throw new EditorBadRequestException("rawYaml 不能为空");
        }
        saveCharacter(folder, parseSettings(rawYaml));
    }

    /** 新建一张卡：建目录 + 写 {@code settings.yml}。已存在 → 400。 */
    public void createCharacter(String folder, Map<String, Object> fields) {
        Path dir = resolveDir(folder);
        if (Files.exists(dir)) {
            throw new EditorBadRequestException("角色目录已存在: '" + folder + "'");
        }
        writeSettings(dir, fields == null ? new LinkedHashMap<>() : fields);
        log.info("[CharacterCardService] 角色卡已新建 {}", folder);
    }

    /**
     * 整目录删除（递归）。不存在 → 404；目录名不合法 → 400。
     *
     * <p>每个待删路径都重新校验前缀：宁可中途 400，也不能删到 {@code game_data/} 之外去。
     */
    public void deleteCharacter(String folder) {
        Path dir = resolveDir(folder);
        if (!Files.isDirectory(dir)) {
            throw new CharacterNotFoundException("角色目录不存在: '" + folder + "'");
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                if (!path.startsWith(root) || path.equals(root)) {
                    throw new EditorBadRequestException("拒绝删除角色卡目录之外的路径: " + path);
                }
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new EditorBadRequestException("角色目录删除失败: " + e.getMessage());
        }
        log.info("[CharacterCardService] 角色卡已删除 {}", folder);
    }

    // ============================================================
    // 启动同步（规格 R1a §4.1）
    // ============================================================

    /**
     * 启动时把 4 张卡的展示类字段同步进 {@code interviewer} 表。
     *
     * <p>只覆盖展示类字段（name / subtitle / card_title / card_info / scale / offset_x / offset_y /
     * avatar_mode / bubble_top / bubble_left）；{@code persona_prompt}、{@code opening}、
     * {@code follow_up}、{@code example_dialogue}、{@code display_role} 这些本项目扩展字段
     * <b>一个都不动</b> —— 它们是人设与面试流程的真相源，被 yml 覆盖会丢。
     *
     * <p>表里没有对应 code 时不自动建（避免脏数据），只 warn。
     */
    @Transactional
    public void syncToInterviewers() {
        for (CharacterCardSummary card : listCharacters()) {
            String folder = card.folder();
            String code = FOLDER_TO_CODE.get(folder);
            if (code == null) {
                log.warn("[CharacterCardService] 角色卡 {} 没有对应的 interviewer.code，跳过同步", folder);
                continue;
            }
            Optional<Interviewer> found = interviewerRepository.findByCode(code);
            if (found.isEmpty()) {
                log.warn("[CharacterCardService] interviewer 表里没有 code={}（角色卡 {}），跳过同步", code, folder);
                continue;
            }
            CharacterCard detail = getCharacter(folder);
            Interviewer entity = found.get();
            entity.setName(detail.aiName());
            entity.setSubtitle(detail.aiSubtitle());
            entity.setCardTitle(detail.title());
            entity.setCardInfo(detail.info());
            if (detail.scale() != null) {
                entity.setScale(detail.scale());
            }
            if (detail.offsetX() != null) {
                entity.setOffsetX(detail.offsetX());
            }
            if (detail.offsetY() != null) {
                entity.setOffsetY(detail.offsetY());
            }
            if (detail.avatarMode() != null) {
                entity.setAvatarMode(detail.avatarMode());
            }
            if (detail.bubbleTop() != null) {
                entity.setBubbleTop(detail.bubbleTop());
            }
            if (detail.bubbleLeft() != null) {
                entity.setBubbleLeft(detail.bubbleLeft());
            }
            interviewerRepository.save(entity);
            log.info("[CharacterCardService] 角色卡 {} → interviewer.{} 展示字段已同步", folder, code);
        }
    }

    // ============================================================
    // 内部工具：路径
    // ============================================================

    /**
     * 解析角色卡根目录。
     *
     * <p>{@code app.characters-dir} 是相对路径，而后端 jar（{@code scripts/dev.sh} 里
     * {@code cd backend && java -jar}）与单测（surefire 的工作目录是 {@code backend/}）
     * 都在 {@code backend/} 下启动，真实的资产却在项目根 —— 所以先按字面量找，
     * 找不到再往上翻一级找。两处都找不到时按字面量返回，后续操作自然报错。
     */
    private static Path resolveRoot(String charactersDir) {
        Path direct = Path.of(charactersDir).toAbsolutePath().normalize();
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromParent = Path.of("..").resolve(charactersDir).toAbsolutePath().normalize();
        if (Files.isDirectory(fromParent)) {
            return fromParent;
        }
        log.warn("[CharacterCardService] 角色卡目录 {} 不存在（也未在上一级找到），按字面量使用", direct);
        return direct;
    }

    /** 根目录下的直接子目录，按目录名排序（顺序稳定，前端列表不会抖）。 */
    private List<Path> childDirectories() {
        if (!Files.isDirectory(root)) {
            log.warn("[CharacterCardService] 角色卡目录不存在: {}", root);
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException e) {
            log.warn("[CharacterCardService] 读取角色卡目录失败 {}：{}", root, e.getMessage());
            return List.of();
        }
    }

    /**
     * 目录名 → 目录路径（规格 R1a §4.2）。
     *
     * <p>空白 / 含 {@code . / \} / 超长一律 400：{@code ..} 含点会被挡在这里，
     * 解析后再查一次前缀，双保险，保证删不到 {@code game_data/} 之外。
     */
    private Path resolveDir(String folder) {
        if (folder == null || folder.isBlank()) {
            throw new EditorBadRequestException("角色目录名不能为空");
        }
        String name = folder.trim();
        if (name.length() > MAX_FOLDER_LENGTH) {
            throw new EditorBadRequestException("角色目录名不能超过 " + MAX_FOLDER_LENGTH + " 个字符");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.' || c == '/' || c == '\\') {
                throw new EditorBadRequestException("角色目录名不能包含 . / \\ : '" + folder + "'");
            }
        }
        Path dir = root.resolve(name).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new EditorBadRequestException("角色目录名不合法: '" + folder + "'");
        }
        return dir;
    }

    // ============================================================
    // 内部工具：yml 读写
    // ============================================================

    /** 读 {@code settings.yml}；文件缺失 / 不是 yml 对象 / IO 失败都抛异常，由上层决定跳过还是 404。 */
    private Map<String, Object> readSettings(Path dir) {
        Path file = dir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            throw new CharacterNotFoundException("角色卡缺少 " + SETTINGS_FILE + ": " + dir.getFileName());
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CharacterNotFoundException("角色卡读取失败: " + e.getMessage());
        }
        return parseSettings(text);
    }

    /** 解析 yml 文本；顶层不是对象（比如手滑写成数组）→ 400。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSettings(String text) {
        Object loaded;
        try {
            loaded = yaml().load(text);
        } catch (Exception e) {
            throw new EditorBadRequestException("settings.yml 不是合法 YAML: " + e.getMessage());
        }
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        if (!(loaded instanceof Map)) {
            throw new EditorBadRequestException("settings.yml 顶层必须是键值对象");
        }
        return new LinkedHashMap<>((Map<String, Object>) loaded);
    }

    /** 写 {@code settings.yml.tmp} 再原子替换，对齐原版「先写临时文件再 rename」的防截断写法。 */
    private void writeSettings(Path dir, Map<String, Object> fields) {
        Path target = dir.resolve(SETTINGS_FILE);
        Path tmp = dir.resolve(SETTINGS_FILE + ".tmp");
        String text = dump(fields);
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些文件系统不支持原子移动，退化成普通覆盖
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new EditorBadRequestException("角色卡写入失败: " + e.getMessage());
        }
    }

    /** dump 成 yml 文本：块风格 + 中文不转义（规格 R1a §4）。 */
    private String dump(Map<String, Object> fields) {
        return yaml().dump(fields);
    }

    /**
     * {@link Yaml} 不是线程安全的，每次新建一份。
     *
     * <p>{@code allowUnicode(true)} 必须开：默认 false 会把中文转义成 {@code "\u98CE\u96EA"}，
     * 前端文本框里就没法直接编辑了。
     */
    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        return new Yaml(options);
    }

    // ============================================================
    // 内部工具：字段映射
    // ============================================================

    private static CharacterCardSummary toSummary(String folder, Map<String, Object> f) {
        return new CharacterCardSummary(
                folder,
                stringValue(f.get("ai_name")),
                stringValue(f.get("ai_subtitle")),
                stringValue(f.get("user_name")),
                stringValue(f.get("user_subtitle")),
                stringValue(f.get("title")),
                stringValue(f.get("avatar_mode")));
    }

    private static CharacterCard toCard(String folder, Map<String, Object> f, String rawYaml) {
        return new CharacterCard(
                folder,
                stringValue(f.get("ai_name")),
                stringValue(f.get("ai_subtitle")),
                stringValue(f.get("user_name")),
                stringValue(f.get("user_subtitle")),
                stringValue(f.get("title")),
                stringValue(f.get("avatar_mode")),
                stringValue(f.get("info")),
                doubleValue(f.get("scale")),
                doubleValue(f.get("offset_x")),
                doubleValue(f.get("offset_y")),
                intValue(f.get("bubble_top")),
                intValue(f.get("bubble_left")),
                mapValue(f.get("voice_models")),
                f.get("body_part"),
                f.get("clothes"),
                rawYaml);
    }

    /** null 直接给 null，其余转字符串（yml 里数字型字段写错成文本时也不至于整个 500）。 */
    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 只认真正的 Map，其它（null / 列表 / 标量）一律 null，避免把整张卡打成 500。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?>) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return null;
    }
}

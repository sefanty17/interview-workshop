package cn.edu.zjut.interview.importer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 导入期使用的全部映射表（规格 B5-BE §2.3 / §2.4），集中成常量，便于对照与单测。
 *
 * <p>三张表都只做「查表命中才改写」，命中不了就原样保留并进报告——导入器不猜、
 * 不兜底、不填默认图（规格 §五 禁区二）。
 */
public final class ResourceMapping {

    /** 引擎注册的 16 种事件类型，与 {@code InterviewEngine#create} 的 switch 分支一一对应。 */
    public static final Set<String> EVENT_TYPES = Set.of(
            "ai_dialogue", "input", "narration", "free_dialogue", "modify_character", "chapter_end",
            "choices", "dialogue", "set_variable", "player", "present_pic",
            "background", "background_effect", "music", "sound", "ambient");

    /** 章节链的终止哨兵，引擎已认，不做悬空校验。 */
    public static final String END_SENTINEL = "end";

    /** 剧本里唯一的角色目录名，引擎 {@code roleId()} 已映射成主角色。 */
    public static final String MAIN_CHARACTER = "MAIN";

    /** 未识别角色文件夹时回落的面试官（规格 §2.3：其余未识别 → strict 风雪）。 */
    public static final String DEFAULT_INTERVIEWER = "strict";

    /**
     * 本项目映射规则：{@code adventure.bound_character_folder} → 本地 {@code interviewer.code}。
     *
     * <p>只映射库里确实存在的角色卡；新增角色卡时同步这张表，否则一律回落 strict。
     */
    public static final Map<String, String> CHARACTER_FOLDERS = Map.of(
            "诺一钦灵", "friendly",
            "风雪", "strict");

    /**
     * 本项目映射规则：原版粒子取值（大小写不敏感 + 常用中文名）→ 前端粒子注册表 key（小写）。
     *
     * <p>原版 {@code known_effects.rs} 的 canonical 写法是 {@code StarField}（大写 F），
     * 但存量剧本里大量写成 {@code Starfield}，前端 {@code GameBackground.vue} 按
     * {@code trim().toLowerCase()} 比较，所以这里统一落到小写。
     */
    public static final Map<String, String> EFFECTS = effects();

    /** 明确表示「没有特效」的取值，归一成 {@code none}，不该进报告。 */
    public static final Set<String> CLEARING_EFFECTS = Set.of("none", "");

    /**
     * 本项目映射规则：原版全局背景文件名 → 本地 {@code /backgrounds/} 文件名。
     *
     * <p>只收本仓库 {@code frontend/public/backgrounds/} 里真实存在的三张图。剧本自带
     * {@code Assets/Backgrounds/} 里的图在导入时另建映射（见 {@link #resolveBackground}），
     * 优先级高于这张表；两边都没有才算「资源不存在于仓库」。
     */
    public static final Map<String, String> BACKGROUNDS = Map.of(
            "夜晚.webp", "/backgrounds/night.webp",
            "白天.webp", "/backgrounds/day.webp",
            "占卜摊2.webp", "/backgrounds/booth.webp");

    /** 剧本自带资源的落盘位置：{@code frontend/public/backgrounds/scripts/<scriptCode>/}。 */
    public static final String SCRIPT_ASSET_PREFIX = "/backgrounds/scripts/";

    /** 认得的图片扩展名（不区分大小写）。 */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("webp", "png", "jpg", "jpeg", "gif");

    private ResourceMapping() {
    }

    private static Map<String, String> effects() {
        Map<String, String> m = new LinkedHashMap<>();
        // canonical 写法
        m.put("starfield", "starfield");
        m.put("sakura", "sakura");
        m.put("rain", "rain");
        m.put("snow", "snow");
        m.put("fireflies", "fireflies");
        m.put("fireworks", "fireworks");
        m.put("fog", "fog");
        m.put("meteorshower", "meteorshower");
        m.put("blizzard", "blizzard");
        m.put("drizzle", "drizzle");
        m.put("thunderstorm", "thunderstorm");
        m.put("ba", "ba");
        // 中文别名（原版剧本编辑器里可选的中文名）
        m.put("星空", "starfield");
        m.put("樱花", "sakura");
        m.put("雨", "rain");
        m.put("雪", "snow");
        m.put("萤火虫", "fireflies");
        m.put("烟花", "fireworks");
        m.put("雾", "fog");
        m.put("流星雨", "meteorshower");
        m.put("暴风雪", "blizzard");
        m.put("细雨", "drizzle");
        m.put("雷雨", "thunderstorm");
        return Map.copyOf(m);
    }

    /** 特效原值 → 前端 key；{@code None} 之类清空值归一成 {@code none}；其余返回空。 */
    public static Optional<String> mapEffect(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (CLEARING_EFFECTS.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return Optional.of("none");
        }
        return Optional.ofNullable(EFFECTS.get(trimmed.toLowerCase(Locale.ROOT)));
    }

    /** 背景解析结果：{@code url} 是前端可直接用的绝对路径，{@code origin} 是 {@code script} / {@code global}。 */
    public record Background(String url, String origin) {
    }

    /**
     * 背景名 → 本地路径。查找顺序（先命中先返回）：
     * <ol>
     *   <li>剧本自带 {@code Assets/Backgrounds/}（{@code scriptAssets}）
     *   <li>全局三张图映射表 {@link #BACKGROUNDS}
     * </ol>
     * 每一步都先用全名精确匹配（大小写不敏感），再退回「同文件名不同扩展名」——
     * 原版 {@code 占卜摊2.png} 与 {@code 占卜摊2.webp} 指的是同一张图。同名多义时不猜，返回空。
     *
     * <p>返回空表示仓库里真的没有，调用方进 yellow；本方法不编路径、不填默认图。
     */
    public static Optional<Background> resolveBackground(String raw, Map<String, String> scriptAssets) {
        Optional<String> script = lookup(scriptAssets, raw);
        if (script.isPresent()) {
            return Optional.of(new Background(script.get(), "script"));
        }
        return lookup(BACKGROUNDS, raw).map(url -> new Background(url, "global"));
    }

    /** 精确名 → 大小写不敏感全名 → 同 stem 唯一匹配。 */
    private static Optional<String> lookup(Map<String, String> table, String raw) {
        if (raw == null || table.isEmpty()) {
            return Optional.empty();
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String exact = table.get(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return Optional.of(e.getValue());
            }
        }
        String stem = stem(name);
        String matched = null;
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (!stem(e.getKey()).equalsIgnoreCase(stem)) {
                continue;
            }
            if (matched != null && !matched.equals(e.getValue())) {
                return Optional.empty();
            }
            matched = e.getValue();
        }
        return Optional.ofNullable(matched);
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /** 是不是图片文件（按扩展名判断，大小写不敏感）。 */
    public static boolean isImage(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 角色文件夹名 → 面试官 code；未识别一律 strict。 */
    public static String interviewerCodeFor(String characterFolder) {
        if (characterFolder == null || characterFolder.isBlank()) {
            return DEFAULT_INTERVIEWER;
        }
        return CHARACTER_FOLDERS.getOrDefault(characterFolder.trim(), DEFAULT_INTERVIEWER);
    }

    /** 前端能选的全部粒子 key（报告里给作者看可用值）。 */
    public static List<String> knownEffectKeys() {
        return List.of("starfield", "sakura", "fireworks", "ba", "fireflies", "meteorshower",
                "drizzle", "rain", "thunderstorm", "snow", "blizzard", "fog");
    }
}

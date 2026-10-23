package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.dto.ScriptCharacters;
import cn.edu.zjut.interview.dto.ValidationReportView;
import cn.edu.zjut.interview.dto.schema.EventSpecView;
import cn.edu.zjut.interview.dto.schema.FieldSpecView;
import cn.edu.zjut.interview.dto.schema.ScriptSchemaView;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 剧本校验器。1:1 迁移自 {@code script_engine/validate.rs}（原版 1732 行）。
 *
 * <p>目标是把引擎里所有**静默失败**变成作者能看见的一条诊断。判定逻辑尽量
 * 复用引擎自己的函数（{@code resolve_script_media} 查素材、{@code parse_variable_action}
 * 解析表达式、{@code KNOWN_EFFECTS} 判特效），避免校验器和运行时各说一套。
 *
 * <p>诊断分三级：
 * <ul>
 *   <li>{@code error} —— 一定会出问题（跑不通、跳不过去、素材缺失）</li>
 *   <li>{@code warn} —— 很可能不是作者的意图（写了不生效的字段、孤儿章节）</li>
 *   <li>{@code info} —— 提示性的（遗留字段、可疑但合法的写法）</li>
 * </ul>
 *
 * <p>保存时不拦，只在「试玩 / 导出」时拦 error。
 *
 * <h2>本项目不产生的 6 个码（规格 E1b §4.1）</h2>
 *
 * <p>原因都是原版依赖的实体在本项目不存在。这六个码在下面以常量形式出现（{@link #NOT_PRODUCED_CODES}）
 * 并在对应位置留了分支或注释，但走不到 —— <b>不是漏了</b>：
 * <table border="1">
 *   <tr><th>码</th><th>原版依赖</th><th>本项目为何不产生</th></tr>
 *   <tr><td>{@code chapter.unreadable}</td><td>{@code Chapters/<id>.yaml} 文件读不出</td>
 *       <td>章节在 DB 里，查到就是可读</td></tr>
 *   <tr><td>{@code chapter.bad_shape}</td><td>{@code ChapterDoc::from_json} 形状不对</td>
 *       <td>我们的 {@code events} 列本身就是 JSON 数组</td></tr>
 *   <tr><td>{@code character.no_role_key}</td><td>剧本内 {@code characters/<目录>/settings.yml} 的
 *       {@code script_role_key}</td><td>没有「剧本内角色设定文件」这个实体（角色统一在 interviewer 表）</td></tr>
 *   <tr><td>{@code character.no_persona}</td><td>同上，{@code system_prompt} 为空</td><td>同上</td></tr>
 *   <tr><td>{@code achievement.id_conflicts_builtin}</td><td>{@code builtin_achievement_ids()}</td>
 *       <td>本项目没有内置成就系统（成就 Tab 未迁移），内置集合恒空</td></tr>
 *   <tr><td>{@code config.unreadable}</td><td>{@code story_config.yaml} 读不出</td>
 *       <td>剧本在 DB 里，404 在校验前就返回了</td></tr>
 * </table>
 *
 * <h2>其余技术栈差异（规格 E1b §4.3）</h2>
 * <ul>
 *   <li>{@code ScriptModes}（{@code .agent/modes}）：本项目没有这套机制，固定按「未声明」处理 ——
 *       {@code asset.missing} 恒 Warn 且<b>不追加</b>「（素材模式允许缺口…）」后缀，
 *       {@code character.unknown} 恒 Warn 且<b>不追加</b>「（角色卡模式允许缺失…）」后缀。</li>
 *   <li>{@code collect_script_characters}：改用 {@code interview_script.characters} 列的 JSON 数组；
 *       空集合时所有非 {@code MAIN} 的 character 引用都会报 {@code character.unknown}(Warn)。</li>
 *   <li>{@code resolve_script_media}：素材扁平落盘在 {@code <assetTarget>/<code>/}，见 {@link MediaResolver}。</li>
 * </ul>
 */
@Component
public class ScriptValidator {

    // ============================================================
    // 不产生的 6 个码（规格 E1b §4.1）：定义常量 + 留分支入口，但走不到
    // ============================================================

    private static final String CODE_CONFIG_UNREADABLE = "config.unreadable";
    private static final String CODE_CHAPTER_UNREADABLE = "chapter.unreadable";
    private static final String CODE_CHAPTER_BAD_SHAPE = "chapter.bad_shape";
    private static final String CODE_CHARACTER_NO_ROLE_KEY = "character.no_role_key";
    private static final String CODE_CHARACTER_NO_PERSONA = "character.no_persona";
    private static final String CODE_ACHIEVEMENT_ID_CONFLICTS_BUILTIN = "achievement.id_conflicts_builtin";

    /** 上面那张表的机器可读版：本项目**永不产生**的 6 个诊断码。 */
    static final List<String> NOT_PRODUCED_CODES = List.of(
            CODE_CONFIG_UNREADABLE,
            CODE_CHAPTER_UNREADABLE,
            CODE_CHAPTER_BAD_SHAPE,
            CODE_CHARACTER_NO_ROLE_KEY,
            CODE_CHARACTER_NO_PERSONA,
            CODE_ACHIEVEMENT_ID_CONFLICTS_BUILTIN);

    /**
     * 原版 {@code builtin_achievement_ids()}：本项目没有内置成就系统，恒空集，
     * 所以 {@code achievement.id_conflicts_builtin} 这条分支存在但永不命中。
     */
    private static final Set<String> BUILTIN_ACHIEVEMENT_IDS = Set.of();

    /** {@code asset.missing} 文案的固定前后缀，逐字照抄原版。 */
    private static final String ASSET_MISSING_PREFIX = "找不到素材「";
    private static final String ASSET_MISSING_TAIL = "。运行时不会报错，只会静默把画面/声音清空";

    /** 引擎的「清空该轨」写法（{@code musicPath: none}），不是素材引用。 */
    private static final String NONE_SENTINEL = "none";

    /**
     * 原版正则 {@code ^\s*(\w+)\s*(=|\+=|-=)\s*(.+?)\s*$}。Rust regex 的 {@code \w} 是 Unicode
     * 语义，所以这里必须开 {@link Pattern#UNICODE_CHARACTER_CLASS}。
     */
    private static final Pattern VAR_ACTION =
            Pattern.compile("^(?U)\\s*(\\w+)\\s*(=|\\+=|-=)\\s*(.+?)\\s*$");

    /** 缺失时引擎有安全默认值的 (事件类型, 字段) 组合，见 {@link #hasEngineDefault}。 */
    private static final List<String[]> ENGINE_DEFAULTS = List.of(
            new String[]{"dialogue", "character"},
            new String[]{"ai_dialogue", "character"},
            new String[]{"free_dialogue", "character"},
            new String[]{"modify_character", "character"},
            new String[]{"chapter_end", "end_type"});

    /** 条件里不支持的运算符。长运算符放前面，命中即停 —— 否则 {@code hp >= 5} 会同时报 >= 和 >。 */
    private static final List<String> BAD_OPS = List.of("&&", "||", ">=", "<=", ">", "<", "!", "(", ")");

    private final InterviewScriptRepository scriptRepository;
    private final ObjectMapper objectMapper;
    private final Path assetRoot;

    public ScriptValidator(InterviewScriptRepository scriptRepository,
                           ObjectMapper objectMapper,
                           @Value("${app.import.asset-target:../frontend/public/backgrounds/scripts}")
                           String assetTarget) {
        this.scriptRepository = scriptRepository;
        this.objectMapper = objectMapper;
        this.assetRoot = Path.of(assetTarget).toAbsolutePath().normalize();
    }

    // ============================================================
    // 入口（validate.rs:181-665）
    // ============================================================

    /**
     * 校验一个剧本。
     *
     * <p>原版签名 {@code validate(data_dir, script_dir, script_key, other_script_names)}；
     * 这里 {@code script_dir} 的目录名 = {@code script.getCode()}，{@code data_dir} 不再需要
     * （素材按扁平目录解析），{@code other_script_names} = 「script_name → 同名的全部剧本 key」的映射，
     * 用于查重 —— 引擎用 script_name 作索引，重名会让其中一些剧本在列表里完全消失。
     * 用 List 存全部冲突者，三个以上剧本重名时能一次性列全，而不是只报最后一个。
     */
    public ValidationReportView validate(InterviewScript script, List<InterviewChapter> chapters) {
        List<Diagnostic> diags = new ArrayList<>();
        ScriptSchemaView schema = ScriptSchemaCatalog.build();

        // 事件类型 → 字段表，用于必填/未知字段检查
        Map<String, List<FieldSpecView>> fieldIndex = new HashMap<>();
        for (EventSpecView e : schema.events()) {
            fieldIndex.put(e.typeKey(), e.fields());
        }
        Set<String> commonKeys = new LinkedHashSet<>();
        for (FieldSpecView f : schema.commonFields()) {
            commonKeys.add(f.key());
        }

        // ---------- story_config ----------
        // [本项目不产生] config.unreadable：剧本在 DB 里，404 在校验前就返回了（规格 E1b §4.1）。
        // 原版这里是 read_story_config 的 Err 分支；我们没有可失败的读取，故只有这一行注释。

        String folderName = script.getCode() == null ? "" : script.getCode();

        String scriptName = script.getScriptName() == null ? "" : script.getScriptName().trim();

        if (scriptName.isEmpty()) {
            diags.add(Diagnostic.script(
                    Severity.WARN,
                    "config.no_script_name",
                    "没有填剧本名，列表里会显示目录名「" + folderName + "」"));
        } else {
            List<String> others = otherScriptNames().getOrDefault(scriptName, List.of());
            List<String> conflicts = new ArrayList<>();
            for (String k : others) {
                if (!k.equals(script.getCode())) {
                    conflicts.add(k);
                }
            }
            if (!conflicts.isEmpty()) {
                diags.add(Diagnostic.script(
                        Severity.ERROR,
                        "config.duplicate_name",
                        "剧本名「" + scriptName + "」与 " + conflicts.size() + " 个剧本重名："
                                + String.join("、", conflicts)
                                + "。引擎用剧本名作索引，重名会导致其中一些在列表里完全消失"));
            }
        }

        // ---------- 章节清单 ----------
        List<String> chapterIds = new ArrayList<>();
        for (InterviewChapter c : chapters) {
            chapterIds.add(c.getCode());
        }
        if (chapterIds.isEmpty()) {
            diags.add(Diagnostic.script(
                    Severity.ERROR,
                    "chapters.empty",
                    "Chapters/ 下没有任何 .yaml 章节文件（引擎只认 .yaml，不认 .yml）"));
            return finish(diags, List.of(), List.of());
        }
        Set<String> chapterSet = new HashSet<>(chapterIds);

        // 原版 intro 缺省是 "main"；本项目 intro_chapter 空白时同样回落 "main"
        String rawIntro = script.getIntroChapter();
        String intro = (rawIntro == null || rawIntro.isBlank()) ? "main" : chapterIdOf(rawIntro);

        if (!chapterSet.contains(intro)) {
            diags.add(Diagnostic.script(
                    Severity.ERROR,
                    "config.intro_missing",
                    "开场章节「" + intro + "」不存在"));
        }

        // 剧本内 NPC 目录名，用于校验 character 引用
        Set<String> knownCharacters = new HashSet<>(ScriptCharacters.parse(script.getCharacters()));

        // 原版这里还调 check_character_personas(...) 检查每个角色的人设（system_prompt）是否填写。
        // 本项目没有「剧本内角色设定文件」这个实体（角色统一在 interviewer 表），整函数不实现，
        // 因此 character.no_role_key / character.no_persona 两个码不产生（规格 E1b §4.1）。

        // ---------- 逐章节 ----------
        List<ChapterEdge> edges = new ArrayList<>();
        TreeSet<String> varsWritten = new TreeSet<>();
        TreeSet<String> varsRead = new TreeSet<>();
        // 「解锁成就」事件的键名：内置成就集合 + 本剧本已出现的键名（成就键名必须唯一）。
        // 内置集合恒空（本项目没有内置成就系统），本剧本内的重名照常报。
        Set<String> achievementIds = new HashSet<>();

        MediaResolver media = new MediaResolver(assetRoot, script.getCode());

        for (InterviewChapter chapter : chapters) {
            String cid = chapter.getCode();

            // [本项目不产生] chapter.unreadable：原版是 resolve_chapter_file 失败。
            // 章节行已经查出来了，没有「文件路径解析不出来」这种情况。
            // [本项目不产生] chapter.bad_shape：原版是 ChapterDoc::from_json 形状不对；
            // 我们的 events 列本身就是 JSON 数组，形状由写入侧保证。

            List<JsonNode> events;
            try {
                events = parseEvents(chapter.getEvents());
            } catch (ChapterParseException e) {
                diags.add(Diagnostic.chapter(
                        Severity.ERROR,
                        "chapter.parse_failed",
                        cid,
                        String.valueOf(e.getMessage())));
                continue;
            }

            if (events.isEmpty()) {
                diags.add(Diagnostic.chapter(
                        Severity.WARN,
                        "chapter.no_events",
                        cid,
                        "章节没有任何事件，运行时会立刻结束整个剧本"));
            }

            int lastIndex = Math.max(events.size() - 1, 0);
            boolean hasChapterEnd = false;

            for (int i = 0; i < events.size(); i++) {
                JsonNode ev = events.get(i);
                if (!ev.isObject()) {
                    diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "event.not_a_map",
                            cid,
                            i,
                            "事件必须是键值映射"));
                    continue;
                }

                JsonNode typeNode = ev.get("type");
                if (typeNode == null || !typeNode.isTextual()) {
                    diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "event.missing_type",
                            cid,
                            i,
                            "事件缺少 type 字段，运行到这里整个剧本会中断"));
                    continue;
                }
                String ty = typeNode.asText();

                List<FieldSpecView> fields = fieldIndex.get(ty);
                if (fields == null) {
                    diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "event.unknown_type",
                            cid,
                            i,
                            "未知事件类型「" + ty + "」，运行到这里整个剧本会中断"));
                    continue;
                }

                // 成就事件的键名唯一性：内置成就与本剧本内都不能重名（重名会覆盖旧定义）
                if ("unlock_achievement".equals(ty)) {
                    JsonNode achNode = ev.get("achievement_id");
                    if (achNode != null && achNode.isTextual()) {
                        String id = achNode.asText().trim();
                        if (!id.isEmpty()) {
                            // 内置集合恒空：这条分支写在这里是为了保持与原版同构，实际走不到。
                            if (BUILTIN_ACHIEVEMENT_IDS.contains(id)) {
                                diags.add(Diagnostic.event(
                                                Severity.ERROR,
                                                CODE_ACHIEVEMENT_ID_CONFLICTS_BUILTIN,
                                                cid,
                                                i,
                                                "「" + id + "」和内置成就重名，会覆盖它的定义，请换个键名")
                                        .withField("achievement_id"));
                            }
                            if (!achievementIds.add(id)) {
                                diags.add(Diagnostic.event(
                                                Severity.ERROR,
                                                "achievement.id_duplicated",
                                                cid,
                                                i,
                                                "本剧本里已有成就「" + id + "」，成就键名不能重复，请换个键名")
                                        .withField("achievement_id"));
                            }
                        }
                    }
                }

                // 必填字段
                for (FieldSpecView f : fields) {
                    if (!f.required()) {
                        continue;
                    }
                    // 引擎有安全默认值的字段（character → MAIN、end_type → linear），缺失照常
                    // 运行，不必填检查会误报。schema 里保持必填是为了编辑器 UX（blankEvent 预填）。
                    if (hasEngineDefault(ty, f.key())) {
                        continue;
                    }
                    if (!present(ev, f.key())) {
                        diags.add(Diagnostic.event(
                                        Severity.ERROR,
                                        "field.required_missing",
                                        cid,
                                        i,
                                        ty + " 缺少必填字段「" + f.label() + "」")
                                .withField(f.key()));
                    }
                }

                // 未知字段（很可能是拼错）
                Set<String> known = new HashSet<>();
                for (FieldSpecView f : fields) {
                    known.add(f.key());
                }
                var names = ev.fieldNames();
                while (names.hasNext()) {
                    String k = names.next();
                    if ("type".equals(k) || known.contains(k) || commonKeys.contains(k)) {
                        continue;
                    }
                    diags.add(Diagnostic.event(
                                    Severity.WARN,
                                    "field.unknown",
                                    cid,
                                    i,
                                    ty + " 上的「" + k + "」不是引擎认识的字段，会被静默忽略")
                            .withField(k));
                }

                // 遗留字段（schema 里 enabled == false 的通用字段）。
                // 由 schema 驱动而不是硬编码字段名，这样加第二个遗留字段只改 schema。
                // 本项目 schema.common_fields 里 condition / duration 都是 enabled，
                // 所以这个循环恒空 —— 因此 field.inert 永不产生。
                // 注意：chapter_end.fields 里的 next 虽然是 disabled，但原版这里只扫 common_fields，
                // 照原版语义不扫事件字段（规格 E1b §4.6）。
                for (FieldSpecView f : schema.commonFields()) {
                    if (f.enabled()) {
                        continue;
                    }
                    if (ev.has(f.key())) {
                        diags.add(Diagnostic.event(
                                        Severity.INFO,
                                        "field.inert",
                                        cid,
                                        i,
                                        f.key() + " 引擎从不读取，写了不生效（保存时会原样保留）")
                                .withField(f.key()));
                    }
                }

                // condition
                String commonCondition = textOf(ev, "condition");
                if (commonCondition != null) {
                    checkCondition(commonCondition, cid, i, diags, varsRead);
                }

                // 逐类型细查
                switch (ty) {
                    case "background", "present_pic", "music", "sound", "ambient" -> {
                        checkAsset(media, ev, ty, cid, i, diags);
                        // music 事件的播放速度：超范围会失真或被浏览器拒绝，提前告警
                        if ("music".equals(ty)) {
                            JsonNode speedNode = ev.get("playbackSpeed");
                            if (speedNode != null && speedNode.isNumber()) {
                                double speed = speedNode.asDouble();
                                if (speed <= 0.0 || speed > 4.0) {
                                    diags.add(Diagnostic.event(
                                                    Severity.WARN,
                                                    "music.bad_speed",
                                                    cid,
                                                    i,
                                                    "播放速度 " + formatNumber(speed)
                                                            + " 超出建议范围（0–4）。≤0 无效，>2 通常会失真")
                                            .withField("playbackSpeed"));
                                }
                            }
                        }
                    }
                    case "background_effect" -> {
                        String effect = textOf(ev, "effect");
                        if (effect == null) {
                            effect = "";
                        }
                        boolean clearing = effect.isEmpty() || "none".equalsIgnoreCase(effect);
                        if (!clearing && !ScriptSchemaCatalog.KNOWN_EFFECTS.contains(effect)) {
                            String correct = null;
                            for (String k : ScriptSchemaCatalog.KNOWN_EFFECTS) {
                                if (k.equalsIgnoreCase(effect)) {
                                    correct = k;
                                    break;
                                }
                            }
                            // 大小写不对 → 打开章节时前端会自动纠错为规范写法，故只给 Info；
                            // 真未知特效 → 前端无法纠错，给 Warn（上游：纠不能纠错都 warning 即可）
                            if (correct != null) {
                                diags.add(Diagnostic.event(
                                                Severity.INFO,
                                                "effect.case",
                                                cid,
                                                i,
                                                "特效「" + effect + "」大小写不对；在编辑器打开本章节时会自动纠正为「"
                                                        + correct + "」")
                                        .withField("effect"));
                            } else {
                                diags.add(Diagnostic.event(
                                                Severity.WARN,
                                                "effect.unknown",
                                                cid,
                                                i,
                                                "特效「" + effect
                                                        + "」不是内置特效，引擎会清空当前特效。可从编辑器的特效下拉里选已支持的项")
                                        .withField("effect"));
                            }
                        }
                    }
                    case "choices" -> checkChoices(ev, cid, i, diags, varsWritten, varsRead);
                    case "set_variable" -> checkSetVariable(ev, cid, i, diags, varsWritten, varsRead);
                    case "free_dialogue" -> {
                        JsonNode roundsNode = ev.get("max_rounds");
                        long rounds = roundsNode != null && roundsNode.isIntegralNumber()
                                ? roundsNode.asLong() : -1L;
                        String endLine = textOf(ev, "end_line");
                        if (endLine == null) {
                            endLine = "结束";
                        }
                        if (rounds <= 0 && endLine.trim().isEmpty()) {
                            diags.add(Diagnostic.event(
                                    Severity.ERROR,
                                    "free_dialogue.no_exit",
                                    cid,
                                    i,
                                    "最大轮数不限且结束语为空，这段自由对话永远无法结束"));
                        }
                    }
                    case "chapter_end" -> {
                        hasChapterEnd = true;
                        if (i != lastIndex) {
                            diags.add(Diagnostic.event(
                                    Severity.WARN,
                                    "chapter_end.not_last",
                                    cid,
                                    i,
                                    "章节结束之后还有 " + (lastIndex - i) + " 个事件，它们永远不会执行"));
                        }
                        checkChapterEnd(ev, cid, i, chapterSet, edges, diags, varsRead);
                    }
                    case "modify_character" ->
                        // 引擎只识别 show_character / hide_character，其余动作静默忽略
                        // （modify_character_event.rs 的 `_ => {}`）。schema 的 select 已限定
                        // 编辑器选项，这里兜住手写 YAML / 旧数据写错的情况。
                            checkModifyCharacterAction(ev, cid, i, diags);
                    default -> {
                    }
                }

                // character 引用
                String ch = textOf(ev, "character");
                if (ch != null && !"MAIN".equals(ch) && !knownCharacters.contains(ch)) {
                    // 松紧由角色卡模式决定：只有"只用已有"才算错误，"允许缺失"与"没问过"都只提醒（没问过不该拦人）。
                    // 本项目没有 ScriptModes，固定按"未声明"处理 → 恒 Warn，且不追加「（角色卡模式允许缺失…）」后缀。
                    String message = "角色「" + ch + "」在本剧本的 characters/ 下找不到；写 MAIN 表示当前主角";
                    diags.add(Diagnostic.event(Severity.WARN, "character.unknown", cid, i, message)
                            .withField("character"));
                }
            }

            if (!hasChapterEnd) {
                diags.add(Diagnostic.chapter(
                        Severity.ERROR,
                        "chapter.no_end",
                        cid,
                        "章节缺少「章节结束」事件。引擎会把这当成整个剧本结束，而不是接着下一章"));
            }
        }

        // ---------- 章节图 ----------
        checkGraph(intro, chapterIds, edges, diags);

        // ---------- 变量 ----------
        for (String v : difference(varsRead, varsWritten)) {
            diags.add(Diagnostic.script(
                    Severity.WARN,
                    "variable.never_set",
                    "「" + v + "」这个变量不会正常运作，请使用「设置变量」给它赋值，或检查变量名是不是写错了"));
        }
        for (String v : difference(varsWritten, varsRead)) {
            diags.add(Diagnostic.script(
                    Severity.INFO,
                    "variable.never_read",
                    "变量「" + v + "」被赋值但从未在任何条件里使用"));
        }

        List<String> allVars = new ArrayList<>(union(varsWritten, varsRead));
        allVars.sort(Comparator.naturalOrder());
        return finish(diags, allVars, edges);
    }

    /** 剧本名 → 同名的全部剧本 key（原版 {@code other_script_names}，由调用方扫盘收集后传入）。 */
    private Map<String, List<String>> otherScriptNames() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (InterviewScript s : scriptRepository.findAllByOrderByOrderNumAsc()) {
            String name = s.getScriptName() == null ? "" : s.getScriptName().trim();
            map.computeIfAbsent(name, k -> new ArrayList<>()).add(s.getCode());
        }
        return map;
    }

    /**
     * 章节 events 列 → 事件数组。
     *
     * <p>原版对应的是 {@code read_yaml_as_json}（读不出 → {@code chapter.parse_failed}）。
     * 这里只有「events 列不是合法 JSON 数组」这一种失败，同样报 {@code chapter.parse_failed}。
     */
    private List<JsonNode> parseEvents(String json) throws ChapterParseException {
        if (json == null || json.isBlank()) {
            throw new ChapterParseException("章节 events 为空");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ChapterParseException(String.valueOf(e.getOriginalMessage()));
        }
        if (node == null || !node.isArray()) {
            throw new ChapterParseException("章节 events 不是 JSON 数组");
        }
        List<JsonNode> events = new ArrayList<>(node.size());
        node.forEach(events::add);
        return events;
    }

    /** 章节 events 读不出来（原版 {@code chapter.parse_failed} 的载体）。 */
    private static final class ChapterParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ChapterParseException(String message) {
            super(message);
        }
    }

    // ============================================================
    // finish（validate.rs:667-701）
    // ============================================================

    private ValidationReportView finish(List<Diagnostic> diags,
                                        List<String> variables,
                                        List<ChapterEdge> edges) {
        // error 在前，其次 warn，最后 info；同级按章节 + 事件序。
        // Rust 的 Option 排序是 None < Some，所以 nullsFirst；List.sort 稳定，同级保持插入序。
        diags.sort(Comparator
                .comparingInt((Diagnostic d) -> d.severity().rank())
                .thenComparing(Diagnostic::chapter, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Diagnostic::eventIndex, Comparator.nullsFirst(Comparator.naturalOrder())));

        int errorCount = 0;
        int warnCount = 0;
        int infoCount = 0;
        for (Diagnostic d : diags) {
            switch (d.severity()) {
                case ERROR -> errorCount++;
                case WARN -> warnCount++;
                case INFO -> infoCount++;
            }
        }
        return new ValidationReportView(diags, errorCount, warnCount, infoCount, variables, edges);
    }

    // ============================================================
    // has_engine_default（validate.rs:707-718）
    // ============================================================

    /**
     * 这些字段在 schema 里标了 required，但引擎在缺失时有安全默认值，缺了也能正常跑
     * （四个角色事件 {@code character} 全部 {@code unwrap_or("MAIN")}，{@code chapter_end.end_type}
     * 回落 {@code "linear"}）。schema 保持必填是为了编辑器 UX（blankEvent 预填），校验器跳过它们，
     * 否则手写 YAML 明明能跑却报「缺少必填字段」的误报。
     */
    private static boolean hasEngineDefault(String eventType, String field) {
        for (String[] pair : ENGINE_DEFAULTS) {
            if (pair[0].equals(eventType) && pair[1].equals(field)) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // collect_script_characters（validate.rs:720-742）→ 见规格 4.3
    // ============================================================

    // 原版扫 characters/ 子目录名；本项目用 interview_script.characters 列（ScriptCharacters.parse），
    // 在 validate 里直接算成 knownCharacters，不需要单独的函数。

    // ============================================================
    // check_character_personas（validate.rs:752-817）→ 不实现（规格 4.1）
    // ============================================================

    // ============================================================
    // media_field_of（validate.rs:819-827）
    // ============================================================

    /** 事件类型 → [取路径的字段名, 素材类别]。 */
    private static String[] mediaFieldOf(String ty) {
        return switch (ty) {
            case "background" -> new String[]{"imagePath", "background"};
            case "present_pic" -> new String[]{"imagePath", "pic"};
            case "music" -> new String[]{"musicPath", "music"};
            case "sound" -> new String[]{"soundPath", "sound"};
            default -> new String[]{"ambientPath", "ambient"};
        };
    }

    // ============================================================
    // check_modify_character_action（validate.rs:834-857）
    // ============================================================

    /**
     * modify_character 的动作取值检查。
     *
     * <p>引擎只识别 {@code show_character} / {@code hide_character}，其余动作在 execute 里静默忽略
     * （modify_character_event.rs 的 {@code _ => {}}）。schema 的 select 已经限定了编辑器选项，
     * 这里兜住手写 YAML / 旧数据写错的情况，把静默失败变成可见诊断。
     */
    private static void checkModifyCharacterAction(JsonNode obj, String cid, int i, List<Diagnostic> diags) {
        String action = textOf(obj, "action");
        if (action != null && !"show_character".equals(action) && !"hide_character".equals(action)) {
            diags.add(Diagnostic.event(
                            Severity.WARN,
                            "character.action_unknown",
                            cid,
                            i,
                            "动作「" + action + "」引擎不识别，会被静默忽略。可用值：show_character（登场）/ hide_character（退场）")
                    .withField("action"));
        }
    }

    // ============================================================
    // check_asset（validate.rs:860-943）
    // ============================================================

    private static void checkAsset(MediaResolver media,
                                   JsonNode obj,
                                   String ty,
                                   String cid,
                                   int i,
                                   List<Diagnostic> diags) {
        String[] field = mediaFieldOf(ty);
        String key = field[0];
        String kind = field[1];
        String path = textOf(obj, key);
        if (path == null) {
            path = "";
        }

        // ambient 的 stop=true 会跳过路径解析，空路径表示停全部轨
        if ("ambient".equals(ty)) {
            JsonNode stopNode = obj.get("stop");
            boolean stopping = stopNode != null && stopNode.asBoolean(false);
            if (stopping) {
                return;
            }
            // 播放（未停）但没给路径：引擎会发一个空路径事件、静默无声音。
            // ambientPath 在 schema 里刻意不标必填（「停全部轨」的写法），所以这里单独提示。
            if (path.isEmpty()) {
                diags.add(Diagnostic.event(
                                Severity.WARN,
                                "ambient.no_path",
                                cid,
                                i,
                                "播放环境音但没有给路径，运行时只会发出空事件、没有声音；要停掉全部轨道请开启「停止该轨」")
                        .withField(key));
                return;
            }
        }
        if (path.isEmpty()) {
            return; // 必填检查已经报过了
        }
        // `none` 是引擎的"清空该轨"写法（`musicPath: none` 用来停音乐），不是素材引用，当成缺失报出来是假报警。
        if (NONE_SENTINEL.equalsIgnoreCase(path)) {
            return;
        }

        String found = media.resolve(path, kind);
        if (found == null) {
            // 松紧由用户声明的素材模式决定：只有"只用已有"才算错误，"允许缺失"是用户允许的缺口；没声明时按最宽。
            // 本项目没有 ScriptModes → 恒 Warn，且不追加「（素材模式允许缺口…）」后缀。
            String message = ASSET_MISSING_PREFIX + path + "」" + ASSET_MISSING_TAIL;
            diags.add(Diagnostic.event(Severity.WARN, "asset.missing", cid, i, message).withField(key));
        } else {
            // 解析器会按同主干的其它扩展名兜底，所以引用写错扩展名照样能跑；但对不上号本身值得
            // 记一笔，否则磁盘上那份哪天被改名/删除，这里就会静默变成缺失。
            String actual = fileNameOf(found);
            String referenced = fileNameOf(path);
            if (actual != null && referenced != null && !actual.equalsIgnoreCase(referenced)) {
                diags.add(Diagnostic.event(
                                Severity.INFO,
                                "asset.extension_mismatch",
                                cid,
                                i,
                                "素材「" + path + "」按同名文件加载了「" + actual + "」，建议把引用改成磁盘上的实际文件名")
                        .withField(key));
            }
        }
    }

    // ============================================================
    // file_name_of（validate.rs:946-950）
    // ============================================================

    /**
     * 取路径最后一段（路径本身，不是文件名主干）。
     *
     * <p>等价于 Rust 的 {@code Path::file_name()}：{@code "/"}、{@code "."}、{@code ".."} 返回 null，
     * 尾部的分隔符会被丢掉（{@code "a/b/"} → {@code "b"}）。
     */
    static String fileNameOf(String path) {
        if (path == null) {
            return null;
        }
        int end = path.length();
        while (end > 1 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        String trimmed = path.substring(0, end);
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        return name;
    }

    // ============================================================
    // check_chapter_assets / missing_asset_name（validate.rs:957-995）→ 本批不做（E6 用）
    // ============================================================

    // ============================================================
    // check_condition（validate.rs:998-1078）
    // ============================================================

    /** 条件语法检查 + 变量收集。 */
    private static void checkCondition(String cond0,
                                       String cid,
                                       int i,
                                       List<Diagnostic> diags,
                                       Set<String> varsRead) {
        String cond = cond0.trim();
        if (cond.isEmpty()) {
            return;
        }

        // 只扫**运算符左侧**。右值是任意字符串，`bg == city/night` 里的 / 是合法内容，
        // 早先在整串上找 / + * ( ) 会把它误判成「用了不支持的运算符」并跳过变量收集。
        String var;
        int ne = cond.indexOf("!=");
        int eq = cond.indexOf("==");
        if (ne >= 0) {
            var = cond.substring(0, ne).trim();
        } else if (eq >= 0) {
            var = cond.substring(0, eq).trim();
        } else {
            var = cond;
        }

        // 长运算符放前面，命中即停 —— 否则 `hp >= 5` 会同时报 >= 和 >
        String badOp = null;
        for (String op : BAD_OPS) {
            if (var.contains(op)) {
                badOp = op;
                break;
            }
        }
        if (badOp != null) {
            diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "condition.unsupported_operator",
                            cid,
                            i,
                            "条件里用了不支持的运算符「" + badOp
                                    + "」。只支持「变量 == 值」「变量 != 值」或单独一个变量判断真假——写了别的不会按你的意思执行（没赋过值的变量不会正常运作）")
                    .withField("condition"));
            return;
        }

        if (var.isEmpty()) {
            diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "condition.no_variable",
                            cid,
                            i,
                            "条件「" + cond + "」左侧没有变量名")
                    .withField("condition"));
            return;
        }
        if (var.contains(" ")) {
            diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "condition.bad_variable",
                            cid,
                            i,
                            "变量名「" + var + "」含空格，引擎的变量名不允许空格")
                    .withField("condition"));
            return;
        }
        if (cond.contains("%player%")) {
            diags.add(Diagnostic.event(
                            Severity.WARN,
                            "condition.placeholder_not_replaced",
                            cid,
                            i,
                            "condition 里的 %player% 不会被替换成玩家名")
                    .withField("condition"));
        }
        varsRead.add(var);
    }

    // ============================================================
    // check_actions（validate.rs:1080-1173）
    // ============================================================

    private static void checkActions(JsonNode actions,
                                     String eventType,
                                     String cid,
                                     int i,
                                     List<Diagnostic> diags,
                                     Set<String> varsWritten) {
        for (JsonNode a : actions) {
            if (!a.isObject()) {
                diags.add(Diagnostic.event(
                        Severity.ERROR,
                        "action.not_a_map",
                        cid,
                        i,
                        "action 必须是键值映射"));
                continue;
            }
            String at = textOf(a, "type");
            if (at == null) {
                at = "";
            }
            String content = textOf(a, "content");
            if (content == null) {
                content = "";
            }

            switch (at) {
                case "set_var" -> {
                    if (content.trim().isEmpty()) {
                        // 旧原型形状：只写了 name/value/op，没有 content 表达式。引擎只读
                        // content（parse_variable_action），缺它这段会被静默跳过、变量永远
                        // 写不进去。报「空表达式」会让作者困惑（他明明填了赋值），这里点名真正原因。
                        if (a.has("name") || a.has("value") || a.has("op")) {
                            diags.add(Diagnostic.event(
                                    Severity.ERROR,
                                    "action.legacy_shape",
                                    cid,
                                    i,
                                    "这条设置变量是旧版本遗留下来的写法，运行时会被跳过。请改写成「变量名 等于/加/减 值」的形式，如 flag = warm"));
                        } else {
                            diags.add(Diagnostic.event(
                                    Severity.ERROR,
                                    "action.empty_expression",
                                    cid,
                                    i,
                                    "表达式为空，请填 变量 = 值 / 变量 += 值 / 变量 -= 值"));
                        }
                    } else {
                        Optional<String> name = parseVariableAction(content);
                        if (name.isPresent()) {
                            varsWritten.add(name.get());
                        } else {
                            diags.add(Diagnostic.event(
                                    Severity.ERROR,
                                    "action.bad_expression",
                                    cid,
                                    i,
                                    "变量表达式「" + content
                                            + "」无法解析。格式为 变量 = 值 / 变量 += 值 / 变量 -= 值（只有这三个运算符）"));
                        }
                    }
                }
                case "add_line" -> {
                    if ("set_variable".equals(eventType)) {
                        diags.add(Diagnostic.event(
                                Severity.WARN,
                                "action.not_supported_here",
                                cid,
                                i,
                                "「设置变量」事件只处理 set_var，这里的 add_line 会被静默忽略"));
                    }
                    if (content.trim().isEmpty()) {
                        diags.add(Diagnostic.event(
                                Severity.WARN,
                                "action.empty_content",
                                cid,
                                i,
                                "add_line 的内容为空"));
                    }
                }
                default -> diags.add(Diagnostic.event(
                        Severity.WARN,
                        "action.unknown_type",
                        cid,
                        i,
                        "未知的动作类型「" + at + "」，会被静默忽略"));
            }
        }
    }

    // ============================================================
    // check_choices（validate.rs:1175-1303）
    // ============================================================

    private static void checkChoices(JsonNode obj,
                                     String cid,
                                     int i,
                                     List<Diagnostic> diags,
                                     Set<String> varsWritten,
                                     Set<String> varsRead) {
        JsonNode options = obj.get("options");
        if (options == null || !options.isArray()) {
            return;
        }
        if (options.isEmpty()) {
            diags.add(Diagnostic.event(
                    Severity.ERROR,
                    "choices.empty",
                    cid,
                    i,
                    "选项列表是空的，玩家无从选择"));
            return;
        }

        int last = options.size() - 1;
        Set<String> seenTexts = new HashSet<>();

        for (int oi = 0; oi < options.size(); oi++) {
            JsonNode opt = options.get(oi);
            if (!opt.isObject()) {
                diags.add(Diagnostic.event(
                        Severity.ERROR,
                        "choices.option_not_a_map",
                        cid,
                        i,
                        "第 " + (oi + 1) + " 个选项不是键值映射"));
                continue;
            }

            String text = textOf(opt, "text");
            if (text == null) {
                text = "";
            }
            if (text.isEmpty()) {
                if (oi != last) {
                    // 引擎 process_options 先求值 condition，为假时跳过该选项、后面的选项仍可达，
                    // 所以「带条件」的空文案选项不一定吞掉后续选项 —— 只有无条件时才是确定会吞。
                    String catchCond = textOf(opt, "condition");
                    boolean hasCondition = catchCond != null && !catchCond.trim().isEmpty();
                    if (hasCondition) {
                        diags.add(Diagnostic.event(
                                Severity.INFO,
                                "choices.catch_all_conditional",
                                cid,
                                i,
                                "第 " + (oi + 1)
                                        + " 个选项没有文案但带了条件：条件满足时会匹配任意输入、吞掉后面的选项，条件不满足时才会轮到后面"));
                    } else {
                        diags.add(Diagnostic.event(
                                Severity.WARN,
                                "choices.catch_all_not_last",
                                cid,
                                i,
                                "第 " + (oi + 1)
                                        + " 个选项没有文案。这类选项会匹配任意输入，放在中间会让后面的选项永远选不到"));
                    }
                }
            } else {
                if (!seenTexts.add(text)) {
                    diags.add(Diagnostic.event(
                            Severity.WARN,
                            "choices.duplicate_text",
                            cid,
                            i,
                            "选项文案「" + text + "」重复，后一个永远选不到"));
                }
                if (text.contains("%player%")) {
                    diags.add(Diagnostic.event(
                            Severity.WARN,
                            "choices.placeholder_in_text",
                            cid,
                            i,
                            "第 " + (oi + 1)
                                    + " 个选项的文案里有 %player%，但引擎只替换顶层字段，按钮上会原样显示"));
                }
            }

            if (opt.has("next")) {
                diags.add(Diagnostic.event(
                        Severity.ERROR,
                        "choices.option_next_ignored",
                        cid,
                        i,
                        "第 " + (oi + 1)
                                + " 个选项写了 next，但 choices 不支持选项级跳转，该字段被完全忽略。要按选择分支，请用 set_var 记录选择 + 章节结束的 branching"));
            }

            String cond = textOf(opt, "condition");
            if (cond != null) {
                checkCondition(cond, cid, i, diags, varsRead);
            } else if (opt.has("lock_hint")) {
                // 不可选提示只在条件不满足时才会展示；没有条件则选项永远可选，这句提示是白写
                diags.add(Diagnostic.event(
                        Severity.INFO,
                        "choices.lock_hint_without_condition",
                        cid,
                        i,
                        "第 " + (oi + 1) + " 个选项填了不可选提示，但没有设置条件——选项不会变灰，这句提示永远不会显示"));
            }

            JsonNode actions = opt.get("actions");
            if (actions != null && actions.isArray()) {
                checkActions(actions, "choices", cid, i, diags, varsWritten);
            }
        }
    }

    // ============================================================
    // check_set_variable（validate.rs:1305-1341）
    // ============================================================

    private static void checkSetVariable(JsonNode obj,
                                         String cid,
                                         int i,
                                         List<Diagnostic> diags,
                                         Set<String> varsWritten,
                                         Set<String> varsRead) {
        JsonNode options = obj.get("options");
        if (options == null || !options.isArray()) {
            diags.add(Diagnostic.event(
                    Severity.ERROR,
                    "set_variable.no_options",
                    cid,
                    i,
                    "「设置变量」需要 options 列表。注意它的形状是 options[].actions[]，不是直接写 name/value"));
            return;
        }
        if (options.isEmpty()) {
            diags.add(Diagnostic.event(
                    Severity.WARN,
                    "set_variable.empty",
                    cid,
                    i,
                    "赋值组是空的，这个事件什么都不做"));
        }
        for (JsonNode opt : options) {
            if (!opt.isObject()) {
                continue;
            }
            String cond = textOf(opt, "condition");
            if (cond != null) {
                checkCondition(cond, cid, i, diags, varsRead);
            }
            JsonNode actions = opt.get("actions");
            if (actions != null && actions.isArray()) {
                checkActions(actions, "set_variable", cid, i, diags, varsWritten);
            }
        }
    }

    // ============================================================
    // push_target（validate.rs:1348-1416）
    // ============================================================

    /**
     * 记录一条章节跳转边，顺便校验目标是否存在。
     *
     * <p>原版刻意写成自由函数而不是闭包：闭包会同时可变借用 {@code edges} 和 {@code diags}。
     * Java 没这个限制，但签名顺序照抄，方便与原版逐行对照。
     *
     * @param raw       原始目标字符串
     * @param label     诊断文案里怎么称呼这个跳转（「下一章」/「第 2 个分支」）
     * @param edgeLabel 流程图连线上显示的标签（分支条件 / AI 分支名），linear 为空
     * @param endType   所属章节的 end_type
     */
    private static void pushTarget(String raw,
                                   String label,
                                   String edgeLabel,
                                   String endType,
                                   String cid,
                                   int i,
                                   Set<String> chapterSet,
                                   List<ChapterEdge> edges,
                                   List<Diagnostic> diags) {
        String target = chapterIdOf(raw);

        if ("end".equals(target)) {
            // 引擎只认字面量 "end"（run_script 的 while next_chapter != "end"）。"end.yaml"
            // 会被上面的归一剥成 "end"，但引擎会去读 Chapters/end.yaml —— 存在则继续跑、
            // 不存在则中断，都不是「剧本结束」的语义，跟校验器看到的对不上，报出来。
            if (!"end".equals(raw.trim())) {
                diags.add(Diagnostic.event(
                        Severity.ERROR,
                        "chapter_end.end_suffix",
                        cid,
                        i,
                        label + "写了「" + raw + "」：引擎只认字面量 end，这里会尝试读取 Chapters/"
                                + target + ".yaml。请直接写 end"));
                return;
            }
            edges.add(new ChapterEdge(cid, "end", true, edgeLabel, endType));
            return;
        }
        if (target.isEmpty()) {
            diags.add(Diagnostic.event(
                    Severity.ERROR,
                    "chapter_end.empty_target",
                    cid,
                    i,
                    label + "没有指定目标章节"));
            return;
        }
        if (!chapterSet.contains(target)) {
            diags.add(Diagnostic.event(
                    Severity.ERROR,
                    "chapter_end.dangling",
                    cid,
                    i,
                    label + "指向的章节「" + target + "」不存在"));
            return;
        }
        edges.add(new ChapterEdge(cid, target, false, edgeLabel, endType));
    }

    /** 统一的章节 id 归一：去空白、剥一次 {@code .yaml}。 */
    static String chapterIdOf(String raw) {
        String t = raw == null ? "" : raw.trim();
        return t.endsWith(".yaml") ? t.substring(0, t.length() - ".yaml".length()) : t;
    }

    /**
     * 分支边在流程图上显示的标签：条件优先，其次 AI 分支名，兜底分支写「默认」。
     */
    private static String branchLabel(JsonNode opt, int index) {
        String c = textOf(opt, "condition");
        if (c != null && !c.trim().isEmpty()) {
            return c.trim();
        }
        String n = textOf(opt, "name");
        if (n != null && !n.trim().isEmpty()) {
            return n.trim();
        }
        JsonNode def = opt.get("default");
        if (def != null && def.asBoolean(false)) {
            return "默认";
        }
        return "分支 " + (index + 1);
    }

    // ============================================================
    // check_chapter_end（validate.rs:1418-1612）
    // ============================================================

    private static void checkChapterEnd(JsonNode obj,
                                        String cid,
                                        int i,
                                        Set<String> chapterSet,
                                        List<ChapterEdge> edges,
                                        List<Diagnostic> diags,
                                        Set<String> varsRead) {
        String endType = textOf(obj, "end_type");
        if (endType == null) {
            endType = "linear";
        }

        switch (endType) {
            case "linear" -> {
                // 引擎优先 next，其次 next_chapter
                String next = textOf(obj, "next");
                String nextChapter = textOf(obj, "next_chapter");

                if (next != null && nextChapter != null) {
                    diags.add(Diagnostic.event(
                            Severity.WARN,
                            "chapter_end.both_next_fields",
                            cid,
                            i,
                            "同时写了 next 和 next_chapter，引擎只会用 next"));
                }
                String target = next != null ? next : nextChapter;
                if (target != null) {
                    pushTarget(target, "下一章", "", endType, cid, i, chapterSet, edges, diags);
                } else {
                    diags.add(Diagnostic.event(
                            Severity.WARN,
                            "chapter_end.no_next",
                            cid,
                            i,
                            "linear 但没写下一章，运行时会直接结束整个剧本"));
                }
            }
            case "branching", "ai_judged" -> {
                JsonNode options = obj.get("options");
                if (options == null || !options.isArray()) {
                    diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "chapter_end.no_options",
                            cid,
                            i,
                            endType + " 需要分支列表，否则一定落到「剧本结束」"));
                    return;
                }
                if (options.isEmpty()) {
                    diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "chapter_end.no_options",
                            cid,
                            i,
                            endType + " 的分支列表是空的，一定落到「剧本结束」"));
                }
                boolean hasDefault = false;
                for (int oi = 0; oi < options.size(); oi++) {
                    JsonNode oo = options.get(oi);
                    if (!oo.isObject()) {
                        continue;
                    }

                    if (oo.has("text") || oo.has("actions")) {
                        diags.add(Diagnostic.event(
                                Severity.ERROR,
                                "chapter_end.choice_shaped_option",
                                cid,
                                i,
                                "第 " + (oi + 1)
                                        + " 个分支写成了选项的形状（text/actions）。章节结束的分支只认 condition / next / default（ai_judged 用 name / next / default）"));
                    }

                    JsonNode def = oo.get("default");
                    boolean isDefault = def != null && def.asBoolean(false);
                    if (isDefault) {
                        hasDefault = true;
                    }

                    // ai_judged 的 default 兜底分支**不需要 name** —— 引擎 match_ai_response_options
                    // 先按 name 子串匹配，匹配不到时再单独遍历 default 分支取 next。所以这里
                    // 只对「非 default 且缺 name」的分支告警，否则官方推荐的兜底写法会被误报。
                    if ("ai_judged".equals(endType) && !isDefault && !oo.has("name")) {
                        diags.add(Diagnostic.event(
                                Severity.WARN,
                                "chapter_end.ai_option_no_name",
                                cid,
                                i,
                                "第 " + (oi + 1) + " 个分支没有 name，AI 判定时无法命中它（除非它设了 default 兜底）"));
                    }
                    // ai_judged 只按 name 匹配，分支里的 condition 会被引擎忽略 —— 作者意图
                    // 会静默丢失，这里提示出来（condition 是 common field，field.unknown 查不到）。
                    if ("ai_judged".equals(endType)) {
                        String c = textOf(oo, "condition");
                        if (c != null && !c.trim().isEmpty()) {
                            diags.add(Diagnostic.event(
                                            Severity.INFO,
                                            "chapter_end.ai_condition_ignored",
                                            cid,
                                            i,
                                            "AI 判定分支不读 condition（按 name 匹配），这个条件会被忽略")
                                    .withField("condition"));
                        }
                    }
                    if ("branching".equals(endType)) {
                        String c = textOf(oo, "condition");
                        if (c != null) {
                            checkCondition(c, cid, i, diags, varsRead);
                        } else if (!isDefault) {
                            diags.add(Diagnostic.event(
                                    Severity.WARN,
                                    "chapter_end.branch_no_condition",
                                    cid,
                                    i,
                                    "第 " + (oi + 1)
                                            + " 个分支没有条件：若它带 next 会无条件命中、后面的分支都选不到；不带 next 则会被跳过"));
                        }
                    }

                    String t = textOf(oo, "next");
                    if (t != null) {
                        pushTarget(t, "第 " + (oi + 1) + " 个分支", branchLabel(oo, oi), endType,
                                cid, i, chapterSet, edges, diags);
                    } else {
                        // 引擎 branching 循环对「条件命中但无 next」的分支不 break，会继续尝试
                        // 后面的分支，只有全部落空才走 default/end —— 所以这不是 Error，也不是
                        // 「一定落到剧本结束」。
                        diags.add(Diagnostic.event(
                                Severity.WARN,
                                "chapter_end.branch_no_next",
                                cid,
                                i,
                                "第 " + (oi + 1)
                                        + " 个分支没有 next：命中后它不会生效（引擎会跳过它继续尝试后面的分支）；若这是唯一分支则会直接结束剧本"));
                    }
                }
                if (!hasDefault) {
                    diags.add(Diagnostic.event(
                            Severity.WARN,
                            "chapter_end.no_default_branch",
                            cid,
                            i,
                            "没有设 default 兜底分支。所有条件都不满足时会直接结束整个剧本"));
                }
            }
            default -> diags.add(Diagnostic.event(
                            Severity.ERROR,
                            "chapter_end.unknown_end_type",
                            cid,
                            i,
                            "未知的结束方式「" + endType
                                    + "」，运行时会直接结束整个剧本；可用值：linear / branching / ai_judged")
                    .withField("end_type"));
        }
    }

    // ============================================================
    // check_graph（validate.rs:1615-1732）
    // ============================================================

    /** 可达性、孤儿章节、环。 */
    private static void checkGraph(String intro,
                                   List<String> chapters,
                                   List<ChapterEdge> edges,
                                   List<Diagnostic> diags) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        Map<String, Integer> inbound = new HashMap<>();
        for (String c : chapters) {
            adj.computeIfAbsent(c, k -> new ArrayList<>());
            inbound.putIfAbsent(c, 0);
        }
        for (ChapterEdge e : edges) {
            if (e.isEnd()) {
                continue;
            }
            adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            inbound.merge(e.to(), 1, Integer::sum);
        }

        // 从开场做可达性
        Set<String> reachable = new HashSet<>();
        List<String> stack = new ArrayList<>();
        stack.add(intro);
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            if (!reachable.add(cur)) {
                continue;
            }
            List<String> ns = adj.get(cur);
            if (ns != null) {
                stack.addAll(ns);
            }
        }

        for (String c : chapters) {
            if (reachable.contains(c)) {
                continue;
            }
            boolean hasIn = inbound.getOrDefault(c, 0) > 0;
            String msg = hasIn
                    ? "章节「" + c + "」虽然有别的章节指向它，但从开场章节走不到那些章节，玩家永远到不了这里"
                    : "章节「" + c + "」没有任何章节指向它，玩家永远走不到";
            diags.add(Diagnostic.chapter(Severity.WARN, "graph.unreachable", c, msg));
        }

        // 环检测（DFS 三色）
        Map<String, Integer> mark = new HashMap<>();
        for (String c : chapters) {
            mark.put(c, 0); // 0 = 白
        }
        List<List<String>> found = new ArrayList<>();
        for (String c : chapters) {
            if (mark.getOrDefault(c, 0) == 0) {
                dfsCycle(c, adj, mark, new ArrayList<>(), found);
            }
        }

        if (!found.isEmpty()) {
            diags.add(Diagnostic.script(
                    Severity.WARN,
                    "graph.cycle",
                    "章节之间存在循环：" + String.join(" → ", found.get(0))
                            + "。引擎没有循环检测，玩家可能被困在里面出不来"));
        }
    }

    /**
     * 环检测的一趟 DFS：0 白（未访问）、1 灰（在栈上）、2 黑（已完成）。
     * 找到第一个环就停（Rust 原版 {@code if found.is_some() { return; }}）。
     */
    private static void dfsCycle(String node,
                                 Map<String, List<String>> adj,
                                 Map<String, Integer> mark,
                                 List<String> path,
                                 List<List<String>> found) {
        if (!found.isEmpty()) {
            return;
        }
        mark.put(node, 1);
        path.add(node);
        List<String> ns = adj.get(node);
        if (ns != null) {
            for (String n : ns) {
                int m = mark.getOrDefault(n, 0);
                if (m == 0) {
                    dfsCycle(n, adj, mark, path, found);
                } else if (m == 1) {
                    if (found.isEmpty()) {
                        int start = path.indexOf(n);
                        if (start < 0) {
                            start = 0;
                        }
                        List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
                        cycle.add(n);
                        found.add(cycle);
                    }
                }
            }
        }
        path.remove(path.size() - 1);
        mark.put(node, 2);
    }

    // ============================================================
    // 小工具
    // ============================================================

    /**
     * 字段存在且不是 {@code null} 且不是空串（原版 {@code v.as_str() != Some("")}）。
     *
     * <p>注意 {@code as_str()} 对非字符串返回 None —— 所以数字 {@code 0}、布尔 {@code false}
     * 都算「present」。
     */
    private static boolean present(JsonNode obj, String key) {
        JsonNode v = obj.get(key);
        return v != null && !v.isNull() && !(v.isTextual() && v.asText().isEmpty());
    }

    /** 取字符串字段；不是字符串（含缺失、null、数字、布尔）时返回 null（原版 {@code as_str()}）。 */
    private static String textOf(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual()) {
            return null;
        }
        return v.asText();
    }

    /** {@code a - b}，保持 TreeSet 的字典序（原版 {@code BTreeSet::difference}）。 */
    private static List<String> difference(TreeSet<String> a, TreeSet<String> b) {
        TreeSet<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return new ArrayList<>(out);
    }

    /** {@code a ∪ b}，保持 TreeSet 的字典序。 */
    private static TreeSet<String> union(TreeSet<String> a, TreeSet<String> b) {
        TreeSet<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    /** 数值按 Rust {@code Display} 的样子输出：整数值不带 {@code .0}（5.0 → "5"）。 */
    private static String formatNumber(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 9.007199254740992E15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /**
     * 解析变量表达式 {@code "flag = true"} / {@code "count += 1"}（原版
     * {@code utils/script_function.rs:67-89} 的 {@code parse_variable_action}）。
     *
     * <p>校验器只需要「能不能解析」和变量名，值部分不需求值（{@code random()} 之类的求值不关校验器的事）；
     * 唯一的例外是 {@code random(a, b)}：两个参数必须能解析成整数且 {@code max >= min}，
     * 否则原版 {@code parse_value} 会 Err，这里同样算解析失败。
     *
     * @return 变量名；解析失败返回 {@link Optional#empty()}
     */
    static Optional<String> parseVariableAction(String expr) {
        if (expr == null) {
            return Optional.empty();
        }
        Matcher m = VAR_ACTION.matcher(expr);
        if (!m.matches()) {
            return Optional.empty();
        }
        String op = m.group(2);
        if (!"=".equals(op) && !"+=".equals(op) && !"-=".equals(op)) {
            return Optional.empty();
        }
        String value = m.group(3).trim();
        if (value.startsWith("random(") && value.endsWith(")")) {
            String inner = value.substring("random(".length(), value.length() - 1);
            String[] parts = inner.split(",", -1);
            if (parts.length == 2) {
                try {
                    long min = Long.parseLong(parts[0].trim());
                    long max = Long.parseLong(parts[1].trim());
                    if (max < min) {
                        return Optional.empty();
                    }
                } catch (NumberFormatException e) {
                    // 原版 parse_value 对 random 的参数解析失败直接 Err
                    return Optional.empty();
                }
            }
        }
        return Optional.of(m.group(1));
    }

    /**
     * 素材解析（规格 E1b §4.4）。原版是 {@code resolve_script_media(data_dir, script_dir, path, media)}
     * 按 {@code MediaType} 去各自的子目录找；本项目素材是**扁平落盘**：
     * {@code <assetTarget>/<code>/<文件名>}（见 {@code ScriptYamlImporter.publishAssets}）。
     */
    static final class MediaResolver {

        /** 各类素材认得的扩展名（原版 {@code MediaType::allowed_extensions}）。 */
        private static final Map<String, Set<String>> ALLOWED = Map.of(
                "background", Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp"),
                "pic", Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp"),
                "music", Set.of("mp3", "ogg", "wav", "flac", "m4a"),
                "sound", Set.of("mp3", "ogg", "wav", "flac", "m4a"),
                "ambient", Set.of("mp3", "ogg", "wav", "flac", "m4a"));

        /** 目录里的普通文件名（已排序，保证兜底匹配结果稳定）。 */
        private final List<String> files;

        MediaResolver(Path assetRoot, String scriptCode) {
            this.files = listFiles(assetRoot, scriptCode);
        }

        private static List<String> listFiles(Path assetRoot, String scriptCode) {
            if (scriptCode == null || scriptCode.isBlank()) {
                return List.of();
            }
            Path dir = assetRoot.resolve(scriptCode).normalize();
            if (!dir.startsWith(assetRoot) || !Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
            } catch (IOException e) {
                return List.of();
            }
        }

        /**
         * @return 磁盘上的实际文件名；找不到返回 null
         */
        String resolve(String path, String kind) {
            String name = fileNameOf(path);
            if (name == null) {
                return null;
            }
            // 先精确匹配文件名（忽略大小写，Windows 语义）
            for (String f : files) {
                if (f.equalsIgnoreCase(name)) {
                    return f;
                }
            }
            // 再按「同主干的其它扩展名」兜底（复现原版 resolve_script_media 的行为，
            // 否则 asset.extension_mismatch 这个码永远不会触发）
            String stem = stemOf(name);
            if (stem == null) {
                return null;
            }
            Set<String> exts = ALLOWED.getOrDefault(kind, Set.of());
            for (String f : files) {
                String fStem = stemOf(f);
                String fExt = extOf(f);
                if (fStem != null && fExt != null
                        && fStem.equalsIgnoreCase(stem)
                        && exts.contains(fExt.toLowerCase(Locale.ROOT))) {
                    return f;
                }
            }
            return null;
        }

        private static String stemOf(String name) {
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                return dot == 0 ? null : name;
            }
            return name.substring(0, dot);
        }

        private static String extOf(String name) {
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) {
                return null;
            }
            return name.substring(dot + 1);
        }
    }
}

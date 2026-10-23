package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.editor.Diagnostic;
import cn.edu.zjut.interview.editor.ScriptValidator;
import cn.edu.zjut.interview.editor.Severity;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Skill Agent 的工具定义与分派（OpenAI function-calling 格式）。
 * 1:1 迁移自 {@code crates/ling-chat-main/src/ai_service/skill_agent/tools.rs}。
 *
 * <p>8 个工具（严格对齐 {@code tools.rs:183-190}）：list_skills / read_skill /
 * validate_script / list_files / read_file / write_file / edit_file / delete_file。
 * {@code execute_command}（tools.rs:192）本批<b>不实现</b>——前置依赖与授权未定，
 * 已确认暂缓；分发处留显式分支返回拒绝信息（见 {@link #execute}），不许偷偷实现。
 *
 * <p>三道闸门（顺序对齐原版）：
 * <ol>
 *   <li>{@link #nameRefusal} —— 白名单闸门：本轮不允许的工具一律拒绝（含未知工具名）；</li>
 *   <li>{@link #foreignPackageRefusal} —— 未绑定会话不许往已存在的剧本包里写；</li>
 *   <li>{@link #draftScopeRefusal} —— 大纲类任务不许往章节细节稿里写正文。</li>
 * </ol>
 */
public class SkillAgentTools {

    /** 剧本包在磁盘上的路径标记（本项目 {@code game_data/scripts/<key>/}）。 */
    private static final String SCRIPT_PATH_MARKER = "/scripts/";

    /** 章节细节稿的相对路径（原版 {@code stage::CHAPTER_DETAILS_REL_PATH}）。 */
    private static final String CHAPTER_DETAILS_REL_PATH = ".agent/chapter-details.md";

    /** 技能 SKILL.md 的大小上限（对齐 {@code skills.rs::MAX_SKILL_BYTES}）。 */
    private static final long MAX_SKILL_BYTES = 512 * 1024L;
    /** 原版 {@code skills.rs::MAX_SKILLS}。 */
    private static final int MAX_SKILLS = 200;

    /** 文件沙箱。 */
    private final SkillAgentFileTools fileTools;
    /** 技能库根目录（{@code game_data/skills}）。 */
    private final Path skillsDir;
    private final InterviewScriptRepository scriptRepository;
    private final InterviewChapterRepository chapterRepository;
    private final ScriptValidator validator;

    public SkillAgentTools(SkillAgentFileTools fileTools,
                           Path skillsDir,
                           InterviewScriptRepository scriptRepository,
                           InterviewChapterRepository chapterRepository,
                           ScriptValidator validator) {
        this.fileTools = fileTools;
        this.skillsDir = skillsDir;
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.validator = validator;
    }

    // ───────────────────────── 类型 ─────────────────────────

    /** 一次工具调用的结果：{@code ok=false} 时 {@code error} 与 {@code output} 同文（都要给 LLM 看）。 */
    public record ToolResult(boolean ok, String output, String error) {

        static ToolResult success(String output) {
            return new ToolResult(true, output, null);
        }

        static ToolResult failure(String message) {
            return new ToolResult(false, message, message);
        }
    }

    /**
     * 本项的作用域（对齐 {@code tools.rs::ItemScope}）：是否产出能跑的章节、真正执行的任务类型。
     */
    public record ItemScope(boolean allowRunnable, SkillAgentRouter.TaskKind kind) {
    }

    /** 一次运行的上下文（对齐 {@code core.rs::SkillAgentRunContext} 里工具层用到的字段）。 */
    public record RunContext(String scriptKey, List<String> existingScriptKeys) {
    }

    // ───────────────────────── 工具定义（对齐 tools.rs:29-133） ─────────────────────────

    /** 全部 8 个工具的定义；只把 {@code allowed} 里的发给模型（藏能力靠这一步）。 */
    public List<SkillAgentLlmClient.ToolDef> toolDefinitions(List<String> allowed) {
        List<SkillAgentLlmClient.ToolDef> all = List.of(
                new SkillAgentLlmClient.ToolDef("list_skills",
                        "列出所有可用技能的名称、描述与位置。",
                        SkillAgentLlmClient.ToolDef.emptySchema()),
                new SkillAgentLlmClient.ToolDef("read_skill",
                        "加载某个技能的 SKILL.md 指令到上下文。当任务匹配某个可用技能的描述时，在执行任务前调用它。",
                        SkillAgentLlmClient.ToolDef.schema(
                                java.util.Map.of("name", prop("string", "要加载的技能名（kebab-case）")),
                                "name")),
                new SkillAgentLlmClient.ToolDef("validate_script",
                        "用引擎真实的剧本校验器检查剧本，返回错误/警告/提示诊断。剧本写完、交付之前必须运行本工具，"
                                + "修复所有「错误」后重新校验，直到 error_count == 0。\n\n"
                                + "⚠ 剧本尚未全部写完时（逐章撰写阶段）不要调用本工具：那时它必然报出一批「尚未写完」造成的假错"
                                + "（指向还没写的章节、后续章节还不可达、变量已赋值但还没轮到在后面章节消费、章节为空等）。"
                                + "这些不是你此刻该修的。校验留到全部章节写完后再做。",
                        SkillAgentLlmClient.ToolDef.schema(
                                java.util.Map.of("script_key", prop("string",
                                        "要校验的剧本 key（interview_script.code）。省略时使用当前会话绑定的剧本 key。"
                                                + "新建剧本若尚未绑定，必须显式传入。")))),
                new SkillAgentLlmClient.ToolDef("list_files",
                        "列出指定目录下的文件与子目录。",
                        SkillAgentLlmClient.ToolDef.schema(
                                java.util.Map.of("path", prop("string", "目录路径，绝对路径或相对于文件沙箱根目录")),
                                "path")),
                new SkillAgentLlmClient.ToolDef("read_file",
                        "读取文本文件的内容。",
                        SkillAgentLlmClient.ToolDef.schema(
                                java.util.Map.of("path", prop("string", "文件路径，绝对路径或相对于文件沙箱根目录")),
                                "path")),
                new SkillAgentLlmClient.ToolDef("write_file",
                        "向文件写入内容，自动创建父目录。默认覆盖整个文件；append=true 时追加。"
                                + "单次调用写完整内容；仅当一次写入因参数过长而失败（报错会附带 [诊断] 提示）后才用 append=true 分段补齐。",
                        SkillAgentLlmClient.ToolDef.schema(
                                java.util.Map.of("path", prop("string", "文件路径，绝对路径或相对于文件沙箱根目录"),
                                        "content", prop("string", "要写入的内容（append=true 时为要追加的内容）"),
                                        "append", prop("boolean", "true 表示追加到已有文件末尾，仅用于修复被截断的写入")),
                                "path", "content")),
                new SkillAgentLlmClient.ToolDef("edit_file",
                        "精确替换文件里的一段文本（改一行、改一个词、删掉整整一节都用它）。"
                                + "`old_string` 必须**唯一命中**，否则不改任何东西并报出有几处；`new_string` 留空 = 删除这段。"
                                + "参数很小，不会因为内容过长被截断 —— **要改文件里的一段就用它，别整份重写**。",
                        SkillAgentLlmClient.ToolDef.schema(
                                java.util.Map.of("path", prop("string", "文件路径"),
                                        "old_string", prop("string",
                                                "要被替换掉的原文（必须唯一命中；要删掉一整节就把那一节原文整段放进来）"),
                                        "new_string", prop("string", "替换成的新文本；留空表示删掉 old_string 那一段"),
                                        "replace_all", prop("boolean", "确实要替换全部匹配时才设 true")),
                                "path", "old_string")),
                new SkillAgentLlmClient.ToolDef("delete_file",
                        "删除一个文件（整份）。只能删文件，不能删目录。",
                        SkillAgentLlmClient.ToolDef.schema(
                                java.util.Map.of("path", prop("string", "要删除的文件路径")),
                                "path")));
        return all.stream().filter(t -> allowed.contains(t.name())).toList();
    }

    /** {@code {"type":"string","description":...}} 形状的单个属性。 */
    private static java.util.Map<String, Object> prop(String type, String description) {
        return java.util.Map.of("type", type, "description", description);
    }

    // ───────────────────────── 闸门 ─────────────────────────

    /**
     * 白名单闸门（对齐 {@code tools.rs::name_refusal}）：工具说明已按角色过滤过一遍，
     * 但模型可能照历史里的旧印象硬调 —— 不在 allowed 里的一律拒绝，措辞照搬原版。
     */
    static String nameRefusal(List<String> allowed, String name) {
        if (allowed.contains(name)) {
            return null;
        }
        return "本轮不允许调用 `" + name + "`（当前这一轮没有这个能力；工具清单里只有 "
                + String.join(" / ", allowed) + "）。请用允许的工具，或者如实告诉用户你这一轮做不了什么。";
    }

    /**
     * 未绑定会话想往已经存在的剧本包里写就拦住（对齐 {@code tools.rs::foreign_package_refusal}）：
     * 模型会自己扫盘挑一个「正好缺这一章」的包当成用户的剧本。措辞用
     * {@code tools_foreign_package_refusal_info.md}。
     */
    static String foreignPackageRefusal(String bound, List<String> existing, String path) {
        if (bound != null) {
            return null;
        }
        String key = scriptKeyOf(path);
        if (key == null || !existing.contains(key)) {
            return null;
        }
        return "本会话还没有绑定剧本，不能改已存在的剧本包「" + key + "」。"
                + "要让用户先在编辑器里打开那个剧本、再新建会话（会自动绑定），"
                + "或者让他把剧本 key 说出来并新建一个会话；"
                + "如果是要新建剧本，就写一个新目录下的 story_config.yaml。这一轮先把这件事告诉用户，不要动文件。";
    }

    /** {@code /scripts/<key>/…} → key；不含标记返回 null。 */
    private static String scriptKeyOf(String path) {
        int index = path.indexOf(SCRIPT_PATH_MARKER);
        if (index < 0) {
            return null;
        }
        String rest = path.substring(index + SCRIPT_PATH_MARKER.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /**
     * 大纲类任务不许往细节稿里写正文（对齐 {@code tools.rs::draft_scope_refusal}），
     * 措辞用 {@code tools_draft_scope_refusal_info.md}。
     */
    static String draftScopeRefusal(SkillAgentRouter.TaskKind kind, String tool, String path, boolean blankNew) {
        if (kind != SkillAgentRouter.TaskKind.OUTLINE && kind != SkillAgentRouter.TaskKind.REVISE_OUTLINE) {
            return null;
        }
        String probe = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!probe.endsWith("/" + CHAPTER_DETAILS_REL_PATH)) {
            return null;
        }
        if ("edit_file".equals(tool) && blankNew) {
            return null;
        }
        return "`" + path + "` 不归本项（本项是「" + kind.label() + "」）：大纲类的活只写 `.agent/design.md`"
                + " 里那几行梗概，章节正文是另一项「写正文」的产物。要写/改这一章的正文，让用户单独要一次，"
                + "或把它排进本轮队列；本项只允许用 `edit_file`（`new_string` 留空）删掉那一节"
                + "—— 那是在删大纲时连带删正文。";
    }

    /**
     * 这个路径是不是能跑的剧本文件（对齐 {@code tools.rs::is_runnable_script_path}）：
     * {@code Chapters/} 下的任何文件，或 {@code story_config.yaml}；只按路径字符串判断。
     */
    static boolean isRunnableScriptPath(String path) {
        String p = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String file = p.substring(p.lastIndexOf('/') + 1);
        return file.equals("story_config.yaml") || p.contains("/chapters/");
    }

    // ───────────────────────── 分发（对齐 tools.rs::execute_tool） ─────────────────────────

    /**
     * 执行一次工具调用。每个工具一个处理函数，闸门顺序留在各自的函数里；
     * 任何实现抛出的异常都在这里收口成 {@code ok:false}，绝不向上漏。
     */
    public ToolResult execute(RunContext run, ItemScope item, List<String> allowed, String name, JsonNode args) {
        // execute_command（tools.rs:192）本批确认暂缓不实现：前置依赖（命令审批流 / 沙箱 shell）
        // 与授权方式未定，E7a2 只留显式拒绝分支，防止模型以为有这个能力。
        // 它不在任何 allowed 清单里，必须先于白名单闸门拦下，否则这个分支永远走不到。
        if ("execute_command".equals(name)) {
            return ToolResult.failure("execute_command 未启用");
        }
        String refusal = nameRefusal(allowed, name);
        if (refusal != null) {
            return ToolResult.failure(refusal);
        }
        try {
            return switch (name) {
                case "list_skills" -> listSkills();
                case "read_skill" -> readSkill(args);
                case "validate_script" -> validateScript(run, args);
                case "list_files" -> listFiles(args);
                case "read_file" -> readFile(args);
                case "write_file" -> writeFile(run, item, args);
                case "edit_file" -> editFile(run, item, args);
                case "delete_file" -> deleteFile(run, item, args);
                default -> ToolResult.failure("未知工具: " + name);
            };
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return ToolResult.failure(message);
        }
    }

    // ───────────────────────── 技能工具 ─────────────────────────

    private ToolResult listSkills() {
        List<SkillInfo> skills = findAllSkills(skillsDir);
        if (skills.isEmpty()) {
            return ToolResult.success("没有已安装的技能。");
        }
        StringBuilder lines = new StringBuilder("可用技能:\n");
        for (SkillInfo s : skills) {
            lines.append("- ").append(s.name()).append(" (").append(s.location()).append("): ")
                    .append(s.description()).append('\n');
        }
        return ToolResult.success(lines.toString().stripTrailing());
    }

    private ToolResult readSkill(JsonNode args) {
        String name = textArg(args, "name");
        if (name.isEmpty()) {
            return ToolResult.failure("缺少 name 参数");
        }
        SkillContent loaded = findSkill(skillsDir, name);
        if (loaded == null) {
            return ToolResult.failure("未找到技能: " + name);
        }
        return ToolResult.success("Reading: " + loaded.name()
                + "\nBase directory: " + loaded.baseDirectory()
                + "\n\n" + loaded.content() + "\n\nSkill loaded: " + loaded.name());
    }

    // ───────────────────────── validate_script ─────────────────────────

    private ToolResult validateScript(RunContext run, JsonNode args) {
        // 确定剧本 key：显式参数优先，否则回落会话绑定的剧本。
        String argKey = textArg(args, "script_key").trim();
        String key = argKey;
        if (key.isEmpty()) {
            if (run.scriptKey() == null) {
                return ToolResult.failure("未指定要校验的剧本 key，且当前会话没有绑定剧本。请传入 script_key 参数（剧本 code）。");
            }
            key = run.scriptKey();
        }
        InterviewScript script = scriptRepository.findByCode(key).orElse(null);
        if (script == null) {
            return ToolResult.failure("无法定位剧本「" + key + "」：剧本库中不存在");
        }
        var report = validator.validate(script, chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId()));
        return ToolResult.success(formatValidationReport(key, report));
    }

    /** 把校验报告格式化成给 LLM 看的中文文本块（对齐 {@code tools.rs::format_validation_report}）。 */
    static String formatValidationReport(String key, cn.edu.zjut.interview.dto.ValidationReportView report) {
        final int MAX_ERROR = 100;
        final int MAX_WARN = 40;
        final int MAX_INFO = 10;

        StringBuilder out = new StringBuilder("[校验报告] 剧本：").append(key).append('\n')
                .append("错误 ").append(report.errorCount()).append(" 条 · 警告 ")
                .append(report.warnCount()).append(" 条 · 提示 ").append(report.infoCount()).append(" 条\n");
        out.append(renderGroup(report.diagnostics(), Severity.ERROR, "错误", MAX_ERROR));
        out.append(renderGroup(report.diagnostics(), Severity.WARN, "警告", MAX_WARN));
        out.append(renderGroup(report.diagnostics(), Severity.INFO, "提示", MAX_INFO));
        if (report.errorCount() > 0) {
            out.append("\n校验未通过：请按上述诊断修复后重新运行 validate_script，直到 error_count == 0。");
        } else {
            out.append("\n校验通过（error_count = 0）。");
            if (report.warnCount() > 0) {
                out.append(" 仍建议按诊断处理以下警告。");
            }
        }
        return out.toString();
    }

    /** 一组诊断的渲染（含截断提示），对应原版闭包 {@code render_group}。 */
    private static String renderGroup(List<Diagnostic> diagnostics, Severity severity, String tag, int cap) {
        List<Diagnostic> list = diagnostics.stream().filter(d -> d.severity() == severity).toList();
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n【").append(tag).append("】").append(list.size()).append(" 条\n");
        int shown = 0;
        for (Diagnostic d : list) {
            if (shown >= cap) {
                break;
            }
            out.append("- [").append(tag).append("][").append(d.code()).append("] ")
                    .append(location(d)).append("：").append(d.message()).append('\n');
            shown++;
        }
        int overflow = list.size() - shown;
        if (overflow > 0) {
            out.append("…另有 ").append(overflow).append(" 条").append(tag)
                    .append("未显示（共 ").append(list.size()).append(" 条）\n");
        }
        return out.toString();
    }

    /** 位置描述：章节「X」 · 第 N 个事件 · 字段「Y」；剧本级诊断无位置则留空。 */
    private static String location(Diagnostic d) {
        List<String> parts = new ArrayList<>();
        if (d.chapter() != null) {
            parts.add("章节「" + d.chapter() + "」");
        }
        if (d.eventIndex() != null) {
            parts.add("第 " + (d.eventIndex() + 1) + " 个事件");
        }
        if (d.field() != null) {
            parts.add("字段「" + d.field() + "」");
        }
        return String.join(" · ", parts);
    }

    // ───────────────────────── 文件工具（闸门顺序对齐 tools.rs） ─────────────────────────

    private ToolResult listFiles(JsonNode args) {
        String path = textArg(args, "path");
        if (path.isBlank()) {
            return ToolResult.failure("缺少 path 参数");
        }
        return ToolResult.success(fileTools.listFiles(path));
    }

    private ToolResult readFile(JsonNode args) {
        String path = textArg(args, "path");
        if (path.isBlank()) {
            return ToolResult.failure("缺少 path 参数");
        }
        return ToolResult.success(fileTools.readFile(path));
    }

    private ToolResult writeFile(RunContext run, ItemScope item, JsonNode args) {
        String path = textArg(args, "path");
        String content = textArg(args, "content");
        boolean append = args.path("append").asBoolean(false);
        if (path.isBlank()) {
            return ToolResult.failure("缺少 path 参数");
        }
        String refusal = foreignPackageRefusal(run.scriptKey(), run.existingScriptKeys(), path);
        if (refusal != null) {
            return ToolResult.failure(refusal);
        }
        if (!item.allowRunnable() && isRunnableScriptPath(path)) {
            // 措辞照搬 tools.rs:319 引用的 prompts/tools_execute_tool.md
            return ToolResult.failure("这一轮用户没有要求产出能跑的章节，所以 `" + path + "` 不能写。"
                    + "往 `Chapters/` 目录和 `story_config.yaml` 里写，要用户明说「转成 YAML」"
                    + "（或「落盘」「生成章节」）那一轮才做；这一轮要产出的应该是 `.agent/` 下的稿子"
                    + "（设计稿 / 章节细节稿 / 素材表）。先照用户说的把内容写进对应的稿子；"
                    + "要不要转成 YAML，由本轮最后那一项统一问一次。");
        }
        refusal = draftScopeRefusal(item.kind(), "write_file", path, false);
        if (refusal != null) {
            return ToolResult.failure(refusal);
        }
        return ToolResult.success(fileTools.writeFile(path, content, append));
    }

    private ToolResult editFile(RunContext run, ItemScope item, JsonNode args) {
        String path = textArg(args, "path");
        String old = textArg(args, "old_string");
        String next = textArg(args, "new_string");
        boolean replaceAll = args.path("replace_all").asBoolean(false);
        if (path.isBlank()) {
            return ToolResult.failure("缺少 path 参数");
        }
        if (old.isBlank()) {
            return ToolResult.failure("缺少 old_string（要被替换掉的原文）。要删掉文件里的某一整段（如某一章那一节），"
                    + "就把那一段原文整段放进 `old_string`、`new_string` 留空；"
                    + "一次只改这一处，改不动就一个字都不写、并告诉你命中了几处。");
        }
        String refusal = foreignPackageRefusal(run.scriptKey(), run.existingScriptKeys(), path);
        if (refusal != null) {
            return ToolResult.failure(refusal);
        }
        if (!item.allowRunnable() && isRunnableScriptPath(path)) {
            // 措辞照搬 tools.rs:370-376
            return ToolResult.failure("这一轮用户没有要求产出能跑的章节，所以 `" + path + "` 不能改。"
                    + "要改 `Chapters/` 目录或 `story_config.yaml` 里的东西，"
                    + "得用户明说「转成 YAML」/「改 YAML」（或旧说法「落盘」）那一轮才做。");
        }
        refusal = draftScopeRefusal(item.kind(), "edit_file", path, next.isBlank());
        if (refusal != null) {
            return ToolResult.failure(refusal);
        }
        return ToolResult.success(fileTools.editFile(path, old, next, replaceAll));
    }

    private ToolResult deleteFile(RunContext run, ItemScope item, JsonNode args) {
        String path = textArg(args, "path");
        if (path.isBlank()) {
            return ToolResult.failure("缺少 path 参数");
        }
        String refusal = foreignPackageRefusal(run.scriptKey(), run.existingScriptKeys(), path);
        if (refusal != null) {
            return ToolResult.failure(refusal);
        }
        if (!item.allowRunnable() && isRunnableScriptPath(path)) {
            // 措辞照搬 tools.rs:410-417
            return ToolResult.failure("这一轮用户没有要求产出能跑的章节，所以 `" + path + "` 不能删。"
                    + "要删 `Chapters/` 里的东西，得用户明说「改 YAML」（或旧说法「落盘」）那一轮才做。");
        }
        return ToolResult.success(fileTools.deleteFile(path));
    }

    // ───────────────────────── 技能库扫描（对齐 skills.rs） ─────────────────────────

    /** 技能搜索目录（原版 {@code skills.rs::search_dirs}：技能根 + 两个隐藏子目录）。 */
    public static List<Path> searchDirs(Path skillsRoot) {
        return List.of(skillsRoot,
                skillsRoot.resolve(".agent").resolve("skills"),
                skillsRoot.resolve(".claude").resolve("skills"));
    }

    /**
     * 发现全部技能（对齐 {@code skills.rs::find_all_skills}）：按名去重、项目内优先再按名排序。
     */
    public static List<SkillInfo> findAllSkills(Path skillsRoot) {
        List<SkillInfo> skills = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path dir : searchDirs(skillsRoot)) {
            if (skills.size() >= MAX_SKILLS || !Files.isDirectory(dir)) {
                continue;
            }
            try (var entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    if (skills.size() >= MAX_SKILLS || !Files.isDirectory(entry) || Files.isSymbolicLink(entry)) {
                        continue;
                    }
                    String name = entry.getFileName().toString();
                    if (seen.contains(name)) {
                        continue;
                    }
                    Path skillMd = safeSkillFile(entry);
                    if (skillMd == null) {
                        continue;
                    }
                    String content = readString(skillMd);
                    if (content == null) {
                        continue;
                    }
                    String location = entry.startsWith(skillsRoot) ? "project" : "global";
                    skills.add(new SkillInfo(name, extractYamlField(content, "description"),
                            location, entry.toString()));
                    seen.add(name);
                }
            } catch (IOException e) {
                // 读不出目录就跳过（原版 read_dir 失败 continue）
            }
        }
        skills.sort((a, b) -> {
            boolean ap = "project".equals(a.location());
            boolean bp = "project".equals(b.location());
            return ap != bp ? (ap ? -1 : 1) : a.name().compareTo(b.name());
        });
        return skills;
    }

    /** 按名读取技能（对齐 {@code skills.rs::find_skill}）；找不到返回 null。 */
    public static SkillContent findSkill(Path skillsRoot, String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        for (Path dir : searchDirs(skillsRoot)) {
            Path skillMd = safeSkillFile(dir.resolve(name).normalize());
            if (skillMd == null) {
                continue;
            }
            String content = readString(skillMd);
            if (content != null) {
                return new SkillContent(name, skillMd.getParent().toString(), content);
            }
        }
        return null;
    }

    /** SKILL.md 必须真实落在技能目录内且不超限（对齐 {@code skills.rs::safe_skill_file}）。 */
    private static Path safeSkillFile(Path skillDir) {
        Path skillMd = skillDir.resolve("SKILL.md").normalize();
        if (!skillMd.startsWith(skillDir.normalize()) || !Files.isRegularFile(skillMd)) {
            return null;
        }
        try {
            return Files.size(skillMd) <= MAX_SKILL_BYTES ? skillMd : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 从 YAML frontmatter 取一个字段（对齐 {@code skills.rs::extract_yaml_field}）。 */
    public static String extractYamlField(String content, String field) {
        if (!content.stripLeading().startsWith("---")) {
            return "";
        }
        String prefix = field + ":";
        for (String line : content.lines().skip(1).toList()) {
            String trimmed = line.trim();
            if (trimmed.equals("---")) {
                break;
            }
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim()
                        .replace("\"", "").replace("'", "");
            }
        }
        return "";
    }

    /** 构建 {@code <available_skills>} 块注入系统提示（对齐 {@code skills.rs::build_skills_xml}）。 */
    public static String buildSkillsXml(List<SkillInfo> skills) {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder tags = new StringBuilder();
        for (SkillInfo s : skills) {
            if (tags.length() > 0) {
                tags.append("\n\n");
            }
            tags.append("<skill>\n<name>").append(escapeXml(s.name()))
                    .append("</name>\n<description>").append(escapeXml(s.description()))
                    .append("</description>\n<location>").append(escapeXml(s.location()))
                    .append("</location>\n</skill>");
        }
        return "\n\n<skills_system priority=\"1\">\n<available_skills>\n" + tags
                + "\n</available_skills>\n</skills_system>";
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 取字符串参数；缺失/非字符串返回空串（对齐 {@code args.get("x").and_then(as_str).unwrap_or("")}）。 */
    static String textArg(JsonNode args, String key) {
        if (args == null) {
            return "";
        }
        JsonNode value = args.get(key);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }
}

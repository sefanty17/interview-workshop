package cn.edu.zjut.interview.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每一项任务的作用域（对齐 {@code role.rs::directive / scope_note / boundary_note}）。
 *
 * <p>三样东西各自回答一个问题，缺一样模型就会跑偏：
 * <ol>
 *   <li>{@link #directive}：这一项<b>必须产出什么</b>（role.rs:100-124 的逐字 include 对应关系）；</li>
 *   <li>{@link #scopeNote}：这一项<b>能改哪些文件、不能碰哪些</b>（role.rs:168-201）；</li>
 *   <li>{@link #boundaryNote}：只读轮「不许动文件」的禁令（role.rs:207-219）。</li>
 * </ol>
 *
 * <p><b>路径口径</b>：原版把章节落在 {@code Chapters/<id>.yaml}、剧本配置落在 {@code story_config.yaml}；
 * 本项目章节落 {@code interview_chapter} 表（按 {@code code} 找行）、剧本配置落 {@code interview_script} 表。
 * 两份 {@code role_scope_note_*.md} 与六份 {@code role_directive_*.md} 是原版逐字副本（不许改文件），
 * 所以差异在这里读出来之后做字符串替换。
 */
public final class RoleScope {

    private static final Logger log = LoggerFactory.getLogger(RoleScope.class);

    private RoleScope() {
    }

    /** 六份「本项产出物要求」，按任务类型给（各只一份，没有就空串）。 */
    private static final Map<SkillAgentRouter.TaskKind, String> DIRECTIVES = Map.of(
            SkillAgentRouter.TaskKind.WRITE_CHAPTER, "/agent/prompts/role_directive_write_chapter.md",
            SkillAgentRouter.TaskKind.OUTLINE, "/agent/prompts/role_directive_outline.md",
            SkillAgentRouter.TaskKind.DRAFT_CHAPTER, "/agent/prompts/role_directive_draft_chapter.md",
            SkillAgentRouter.TaskKind.REVISE_OUTLINE, "/agent/prompts/role_directive_revise_outline.md",
            SkillAgentRouter.TaskKind.REVISE_CHAPTER, "/agent/prompts/role_directive_revise_chapter.md",
            SkillAgentRouter.TaskKind.POLISH, "/agent/prompts/role_directive_polish.md");

    private static final String SCOPE_DRAFT_CHAPTER = "/agent/prompts/role_scope_note_draft_chapter.md";
    private static final String SCOPE_WRITE_CHAPTER = "/agent/prompts/role_scope_note_write_chapter.md";

    /** 读过的资源缓存（静态内容，一轮只读一次就够；读不到也缓存空串，避免反复打日志）。 */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /**
     * 这一项必须产出什么；没有对应手册返回空串。
     *
     * <p>原版是 {@code include_str!} 编译期嵌入，这里是运行期读 classpath，所以多一层缓存。
     */
    public static String directive(SkillAgentRouter.TaskKind kind) {
        if (kind == null) {
            return "";
        }
        String resource = DIRECTIVES.get(kind);
        return resource == null ? "" : normalizeResource(read(resource)).strip();
    }

    /**
     * 这一项能动什么：与工具层闸门同一口径的硬约束。
     *
     * <p>{@code target} 是用户点名的章节号；留空时按「点名的这一章」/ {@code <id>} 处理（与原版一致）。
     */
    public static String scopeNote(SkillAgentRouter.TaskKind kind, String target) {
        if (kind == null) {
            return "";
        }
        String t = target == null ? "" : target.trim();
        String chap = t.isEmpty() ? "点名的这一章" : "第 " + t + " 章";
        String id = t.isEmpty() ? "<id>" : t;
        return switch (kind) {
            case OUTLINE -> "只写 `.agent/design.md`（新建剧本那一轮，连带建包写的 "
                    + "`constraints.md` / `assets.md` 骨架也算这一项）。**`.agent/chapter-details.md` "
                    + "一个字都不要写**，章节落库（interview_chapter 表）与 interview_script 表也不许碰。";
            case REVISE_OUTLINE -> "只改 `.agent/design.md` 里点名的那几处（以及 "
                    + "`constraints.md` 里的章数口径）。删大纲那一章时，连带删 `.agent/chapter-details.md` "
                    + "里那一节（往下连带可以）；此外不许碰正文，也不许碰 interview_chapter 表。";
            case DRAFT_CHAPTER -> normalizeResource(read(SCOPE_DRAFT_CHAPTER))
                    .replace("{chap}", chap).strip();
            case WRITE_CHAPTER -> normalizeResource(read(SCOPE_WRITE_CHAPTER))
                    .replace("{id}", id).strip();
            case REVISE_CHAPTER -> "只改或删 interview_chapter 表里 code=" + id + " 的那一行。"
                    + "**改的时候可以往上对齐**：涉及剧情内容时同步 "
                    + "`.agent/chapter-details.md` 里那一节，梗概也因此过时时顺带对齐 `.agent/design.md` 里那一行。"
                    + "**删的时候只删这一章的 YAML** —— 正文与大纲一个字都不许动（往上删不可以）。";
            case CHECK_ASSETS -> "只写 `.agent/assets.md`；别的文件都不许动。";
            case POLISH -> "只出体检报告，**不动任何文件**。";
            case CHAT, COLLECT, SUMMARIZE -> "这一轮的产物就是这段回复，**不动任何文件**。";
        };
    }

    /**
     * 只读轮补一句「不要动文件」的禁令；要动手的轮次返回 {@code null}（没有额外禁令）。
     */
    public static String boundaryNote(SkillAgentRouter.TaskKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case CHAT -> "这一轮跟剧本无关：产出物就是这段回复本身，不要动任何文件；"
                    + "若用户其实想改大纲或改剧情，先问清楚改哪里";
            case COLLECT -> "产出物是向用户提的问题（缺哪条问哪条）；不要写文件、不要编剧情";
            case SUMMARIZE -> "产出物是复述用户的想法 + 还缺哪条信息；不要写文件、不要往下编剧情";
            default -> null;
        };
    }

    /**
     * 原版路径口径 → 本项目口径；{@code {chap}} / {@code {id}} 由调用方再填。
     *
     * <p>顺序不能换：先换带 {@code {id}} 的精确写法，再换剧本配置文件名，最后换目录前缀。
     */
    private static String normalizeResource(String text) {
        return text
                .replace("Chapters/{id}.yaml", "interview_chapter 表里 code={id} 的那一行")
                // 手册里还写过不带大括号的 Chapters/<id>.yaml（如「按它写 `Chapters/<id>.yaml`」）
                .replace("Chapters/<id>.yaml", "interview_chapter 表里 code=<id> 的那一行")
                .replace("story_config.yaml", "interview_script 表")
                .replace("Chapters/", "interview_chapter 表");
    }

    /** 读 classpath 文本；读不到 {@code log.warn} 并返回空串（缺手册不该让整轮崩掉）。 */
    private static String read(String resource) {
        String cached = CACHE.get(resource);
        if (cached != null) {
            return cached;
        }
        String text = "";
        try (InputStream in = RoleScope.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("[RoleScope] 缺资源 {}", resource);
            } else {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("[RoleScope] 读资源失败 {}：{}", resource, e.toString());
        }
        CACHE.put(resource, text);
        return text;
    }
}

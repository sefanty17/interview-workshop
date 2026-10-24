package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.QueueItem;
import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 注入文本：任务块、交接材料与进度行，只读入参、只产文本。
 *
 * <p>1:1 迁移自 {@code skill_agent/stage/prompt.rs}（456 行）里除 {@code hub_doc} 之外的全部公开函数：
 * {@link #buildTaskBlock}（{@code prompt.rs:63-222}）、{@link #deferredQuestion}（{@code 267-274}）、
 * {@link #handoffDigest}（{@code 228-264}）、{@link #renderTextOf}（{@code 277-282}）、
 * {@link #buildRunMaterials}（{@code 291-321}）。
 *
 * <p>五个 {@code stage_build_task_block*.md} 是原版逐字副本，Java 侧读 classpath 而不是
 * {@code include_str!}，拼装顺序严格照 {@code prompt.rs:80-221}。
 */
public final class StagePrompt {

    /** 中间项把"要用户拍板的事"写成这一行，代码原样摘给后面各项（尤其收尾那一项）。 */
    public static final String DEFERRED_MARK = "【留给最后一项问】";

    private static final String STAGE_TASK_BLOCK = read("/agent/prompts/stage_build_task_block.md");
    private static final String STAGE_TASK_BLOCK_2 = read("/agent/prompts/stage_build_task_block_2.md");
    private static final String STAGE_TASK_BLOCK_3 = read("/agent/prompts/stage_build_task_block_3.md");
    private static final String STAGE_TASK_BLOCK_4 = read("/agent/prompts/stage_build_task_block_4.md");
    private static final String STAGE_TASK_BLOCK_5 = read("/agent/prompts/stage_build_task_block_5.md");
    private static final String ACTION_VOCAB = read("/agent/prompts/role_action_vocab.md");
    private static final String DICTATE_NOTE = read("/agent/prompts/role_dictate_note.md");
    private static final String BOUNDARY_BASELINE = read("/agent/prompts/role_boundary_baseline.md");

    private StagePrompt() {
    }

    /**
     * 任务块的入参（原版 {@code TaskBlockInput} 的 Java 化）。
     *
     * @param task      本项任务类型
     * @param handoff   本项的承接判断
     * @param items     本轮队列（{@code plan.items}）
     * @param itemIndex 本项在队列里的下标（从 0 起）
     * @param userMsg   用户这一轮的原话
     * @param skillsDir 技能库目录（本项不注入手册，留着与原版签名一致）
     * @param land      本轮要不要产出能跑章节（由计划里有没有 WriteChapter / ReviseChapter 派生）
     * @param isLast    这一项是不是本轮最后执行的
     * @param skipped   队列里被跳过的项
     * @param allowed   这一轮真正下发的工具清单
     */
    public record TaskBlockInput(
            TaskKind task,
            ScriptFacts.Handoff handoff,
            List<QueueItem> items,
            int itemIndex,
            String userMsg,
            Path skillsDir,
            boolean land,
            boolean isLast,
            List<String> skipped,
            List<String> allowed) {
    }

    // ───────────────────────── 任务块 ─────────────────────────

    /**
     * 本轮的注入块：任务 + 行为要求 + 职责边界 + 承接说明 + 该任务要的那几本手册（按任务注入而非按阶段）。
     *
     * <p><b>本项目的两处固定差异</b>（不许"改回原版"）：
     * <ul>
     *   <li>{@link #materialsOf} 恒为空 —— 角色手册已由 {@code buildSystemPrompt} 注入，
     *       所以最后一步必然走「（本轮不注入任何角色指令。）」那一支；那句话原版就有，照发。</li>
     *   <li>{@code plan.boundary}（路由给的边界）本项目 {@code RoutePlan} 没有这个字段，
     *       按 {@code (Some, None)} 分支渲染。</li>
     * </ul>
     */
    public static String buildTaskBlock(TaskBlockInput input) {
        TaskKind task = input.task();
        ScriptFacts.Handoff handoff = input.handoff();
        List<QueueItem> items = input.items() == null ? List.of() : input.items();
        int itemIndex = input.itemIndex();
        String userMsg = input.userMsg() == null ? "" : input.userMsg();
        boolean land = input.land();
        boolean isLast = input.isLast();
        List<String> skipped = input.skipped() == null ? List.of() : input.skipped();
        List<String> allowed = input.allowed() == null ? List.of() : input.allowed();
        String target = targetAt(items, itemIndex);
        int n = items.size();
        boolean prerequisite = handoff != null && handoff.kind() == ScriptFacts.Handoff.Kind.PREREQUISITE;

        StringBuilder out = new StringBuilder("\n\n【本轮任务】").append(task.label());
        Role role = SkillAgentRouter.roleFor(task);
        if (role != null) {
            out.append("（").append(role.label()).append("）");
        } else {
            out.append("（不涉及剧本）");
        }
        if (!target.isEmpty()) {
            out.append("｜本项目标：").append(target);
        }
        if (n > 1) {
            out.append("　队列第 ").append(itemIndex + 1).append('/').append(n).append(" 项，")
                    .append(prerequisite ? "先补前置（见下）" : "只做这一项");
        }
        if (prerequisite && items.size() > itemIndex) {
            QueueItem original = items.get(itemIndex);
            out.append("\n【这一项为什么换了名字】原定这一项是「")
                    .append(renderTextOf(original.kind(), original.target()))
                    .append("」；").append(handoff.note())
                    .append(" —— 所以本轮先做前置的「").append(task.label())
                    .append("」。同轮会接着做回原任务（见【承接说明】）。");
        }
        if (!skipped.isEmpty()) {
            out.append("\n【队列里做不了的项】").append(String.join("；", skipped))
                    .append("（在回执里逐条说明为什么，别装没看见）");
        }
        if (n > 1) {
            out.append(STAGE_TASK_BLOCK);
        }
        if (isLast) {
            out.append("\n【本轮最后一项】你是本轮最后执行的一项：先把这一项交待完"
                    + "（做了什么 / 发现了什么 / 要用户定什么），**然后**用一句话把本轮每项各自做了什么列全 ——"
                    + "用户只看最后这一段也知道全貌。");
        }
        if (!userMsg.trim().isEmpty()) {
            out.append("\n【用户这一轮的原话】").append(userMsg.trim());
        }
        out.append(STAGE_TASK_BLOCK_2);
        out.append('\n').append(ACTION_VOCAB);
        if (!land && !materialsOf(task).isEmpty() && role != null) {
            out.append('\n').append(DICTATE_NOTE);
        }
        out.append(fill(STAGE_TASK_BLOCK_3, String.join(" / ", allowed)));
        out.append(fill(STAGE_TASK_BLOCK_4, RoleScope.scopeNote(task, target)));
        if (n > 1) {
            out.append("\n\n【本轮共 ").append(n).append(" 项，这是第 ").append(itemIndex + 1)
                    .append(" 项】代码会**逐项**交给对应角色执行："
                            + "**你只管这一项**，其余项由代码另起一段交给对应角色，**别代劳、也别替它们汇报**。");
        }
        out.append("\n\n【回执怎么写】只讲**这一轮盘上发生的改变**：动了哪个文件、改前是什么、改后是什么"
                + "（新增/删掉了哪一章哪一节、把哪一行改成了什么）；盘上**本来就有的**东西不要重新介绍一遍 ——"
                + "比如前面 1~10 章早就写好了、这一轮写的是第 11 章，那就只说第 11 章，别把 1~10 章再列一遍。");
        if (isLast) {
            out.append("你是本轮最后一项：用户要定的事在这里一次说清（其余各项已由代码各自交付）。"
                    + "**交接材料里标着「还没核对到产物」的项，也要如实交代**（说我这一轮没核对到它的产物、"
                    + "缺什么、要他定什么），不许当成做完了。");
        } else {
            // 原版是 format!(include_str!(...), DEFERRED_MARK = DEFERRED_MARK)：命名占位符，不是 {}
            out.append(STAGE_TASK_BLOCK_5.replace("{DEFERRED_MARK}", DEFERRED_MARK));
        }
        String directive = RoleScope.directive(task);
        if (directive != null && !directive.isEmpty()) {
            out.append('\n').append(directive);
        }
        // plan.boundary 本项目没有 → (Some, None) 分支
        String boundaryNote = RoleScope.boundaryNote(task);
        out.append("\n【职责边界】").append(renderBoundary(boundaryNote));
        String handoffText = ScriptFacts.renderHandoff(handoff);
        if (!handoffText.isEmpty()) {
            out.append("\n【承接说明】").append(handoffText);
        }
        out.append(renderMaterials(input.skillsDir(), materialsOf(task)));
        return out.toString();
    }

    /**
     * 该任务要预注入的手册（原版 {@code role.rs::TaskKind::materials}）。
     *
     * <p><b>本项目恒为空</b>：角色手册统一由 {@code buildSystemPrompt} 按角色注入，任务块里不再重列一遍。
     */
    static List<String> materialsOf(TaskKind task) {
        return List.of();
    }

    /** 原版 {@code push_materials}：逐份读入手册并冠以「角色指令」来源头；读不到要把错误告诉模型。 */
    private static String renderMaterials(Path skillsDir, List<String> materials) {
        if (materials.isEmpty()) {
            return "\n\n（本轮不注入任何角色指令。）";
        }
        StringBuilder out = new StringBuilder(
                "\n\n以下技能文档是本轮**必须遵守的角色指令**，不是参考资料；已注入的无需重复读取。");
        for (String rel : materials) {
            Path path = skillsDir == null ? null : skillsDir.resolve(rel);
            String text = path == null ? null : readOrNull(path);
            if (text != null) {
                out.append("\n\n【角色指令 · ").append(rel).append("】\n").append(trimEnd(text));
            } else {
                out.append("\n\n【角色指令缺失 · ").append(rel)
                        .append("】本文件读取失败。其中的规范不得凭记忆代替，"
                                + "也不要静默继续；先告知用户技能文件缺失（可能需要在数据同步里重新勾选）。\n");
            }
        }
        return out.toString();
    }

    /** 原版 {@code role::render_boundary}：边界底线 + 本轮补充（没有补充就只有底线）。 */
    private static String renderBoundary(String supplement) {
        if (supplement == null) {
            return BOUNDARY_BASELINE;
        }
        return BOUNDARY_BASELINE + "\n本轮补充：" + supplement;
    }

    // ───────────────────────── 交接材料 ─────────────────────────

    /**
     * 从一项的回执里摘出 {@link #DEFERRED_MARK} 那一行；没写就没有，不猜也不从正文里反解。
     */
    public static String deferredQuestion(String reply) {
        if (reply == null) {
            return null;
        }
        for (String raw : reply.split("\n", -1)) {
            String line = raw.trim();
            if (line.startsWith(DEFERRED_MARK)) {
                String rest = line.substring(DEFERRED_MARK.length()).trim();
                if (!rest.isEmpty()) {
                    return rest;
                }
                // 原版 map().filter() 是「第一行命中就算」，命中但不剩内容时返回 None
                return null;
            }
        }
        return null;
    }

    /**
     * 交接材料：本轮在前面排过的项 + 它们在账本上的状态 + 中间项留下的待问事项
     * （{@code prompt.rs:228-264}）。
     *
     * @param queueDir 剧本包目录（读 {@code .agent/queue.json}）
     * @param items    本轮队列
     * @param upto     本项前面的项数（本项下标）
     * @param deferred 前面各项留下的待问事项
     */
    public static String handoffDigest(Path queueDir, List<QueueItem> items, int upto, List<String> deferred) {
        List<String> pending = deferred == null ? List.of() : deferred;
        if (upto == 0 && pending.isEmpty()) {
            return "";
        }
        List<StageQueue.Entry> state = StageQueue.readEntries(queueDir);
        StringBuilder out = new StringBuilder();
        if (upto > 0) {
            out.append("\n\n【本轮在你之前排过的项】（这是进度，不是你的任务；你这一项是第 ")
                    .append(upto + 1).append('/').append(items.size()).append(" 项）");
            for (int i = 0; i < upto && i < items.size(); i++) {
                QueueItem item = items.get(i);
                String target = item.target() == null ? "" : item.target().trim();
                String phrase = "没有可核对的产物（话术 / 只读体检这类）";
                for (StageQueue.Entry entry : state) {
                    if (entry.kind() == item.kind() && entry.target().equals(target)) {
                        phrase = StageQueue.markPhrase(entry.mark());
                        break;
                    }
                }
                out.append("\n- 第 ").append(i + 1).append(" 项 ").append(renderTextOf(item.kind(), target))
                        .append(" —— ").append(phrase);
            }
            out.append("\n（这一份只讲进度：哪一项具体做了什么，去看盘上那几个文件；**不要照它去做事**。）");
        }
        if (!pending.isEmpty()) {
            out.append("\n【前面各项留给最后一项问用户的事】");
            for (String q : pending) {
                out.append("\n- ").append(q);
            }
        }
        return out.toString();
    }

    /** 一项的正文（{@code 类型标签：目标}），视图与注入的进度行共用；目标为空时只写类型标签。 */
    public static String renderTextOf(TaskKind kind, String target) {
        String trimmed = target == null ? "" : target.trim();
        if (trimmed.isEmpty()) {
            return kind.label();
        }
        return kind.label() + "：" + trimmed;
    }

    // ───────────────────────── 本轮材料 ─────────────────────────

    /**
     * 本轮动态材料：前半段按「阶段」给（设计稿 / 待写章节 + 上一章收尾），后半段是【落盘进度】。
     *
     * <p><b>阶段判定的实现方式差异</b>：原版有 {@code Stage} 枚举（Setup / Modify / Forge / …），
     * 本项目没有，等价映射如下（只影响本方法前半段，后半段与 {@code prompt.rs:324-393} 逐行一致）：
     * <table border="1">
     *   <tr><th>原版 Stage</th><th>触发条件</th><th>我方等价</th></tr>
     *   <tr><td>{@code Setup} / {@code Modify} 且有 design</td>
     *       <td>{@code task ∈ {OUTLINE, REVISE_OUTLINE, COLLECT, SUMMARIZE, CHAT}}</td>
     *       <td>输出「【现有设计稿】」+ 设计稿全文</td></tr>
     *   <tr><td>{@code Forge}</td><td>{@code task == WRITE_CHAPTER}</td>
     *       <td>输出「【待写章节 · id】」+ 该章设计稿整块，和「【上一章（id）收尾状态】」</td></tr>
     *   <tr><td>其他</td><td>其余</td><td>只输出后半段</td></tr>
     * </table>
     *
     * @param snap    剧本快照；{@code scriptDir} 为 {@code null} 时返回空串
     * @param dataDir 项目数据根目录（{@code config_line} 与素材校验用）
     */
    public static String buildRunMaterials(StageFacts snap, Path dataDir) {
        return buildRunMaterials(snap, null, dataDir);
    }

    /**
     * 同上，额外给本项任务类型（用于选前半段）；{@code task} 为 {@code null} 时只给后半段。
     *
     * <p>{@code dataDir} 目前只在原版 {@code missing_assets_of_written} 里用，本批次未实现那一步（见
     * {@code progressBlock} 的 TODO），签名保留是为了下一批直接接上。
     */
    public static String buildRunMaterials(StageFacts snap, TaskKind task, Path dataDir) {
        if (snap == null || snap.scriptDir() == null) {
            return "";
        }
        Path dir = snap.scriptDir();
        String design = snap.design() == null ? "" : snap.design();

        StringBuilder out = new StringBuilder();
        if (task != null && (task == TaskKind.OUTLINE || task == TaskKind.REVISE_OUTLINE
                || task == TaskKind.COLLECT || task == TaskKind.SUMMARIZE || task == TaskKind.CHAT)
                && !design.isEmpty()) {
            out.append("\n\n【现有设计稿】\n").append(trimEnd(design));
        } else if (task == TaskKind.WRITE_CHAPTER) {
            String next = snap.nextChapter();
            if (next != null) {
                String block = extractChapterBlock(design, next);
                if (block != null) {
                    out.append("\n\n【待写章节 · ").append(next).append("】\n").append(block);
                }
            }
            String prev = snap.lastWritten();
            if (prev != null) {
                String tail = tailState(readChapter(dir, prev));
                if (!tail.isEmpty()) {
                    out.append("\n\n【上一章（").append(prev).append("）收尾状态】\n").append(tail);
                }
            }
        }
        out.append(progressBlock(snap, dir));
        return out.toString();
    }

    /**
     * 【落盘进度】：交接单里的进度事实，{@code constraints} 已读好放在 {@code snap} 里。
     *
     * <p>{@code config_line} 原版读 {@code story_config.yaml}；<b>本项目剧本配置在
     * {@code interview_script} 表</b>（技术栈差异），所以那一行是固定的一句。
     */
    static String progressBlock(StageFacts snap, Path dir) {
        String constraints = snap.constraints();
        ScriptModes modes = ScriptModes.parse(constraints);
        StringBuilder body = new StringBuilder();
        body.append("- ").append(modes.assetDescribe())
                .append("（`.agent/constraints.md` 是唯一来源；要改就改那一行，并检查别处有没有旧说法）\n");
        if (ScriptModes.usesCharacterCards(snap.scriptKey(), dir, constraints)) {
            body.append("- ").append(modes.castDescribe()).append("（同上，改一处就全都对齐）\n");
        }
        String conflict = ScriptModes.conflictNote(constraints);
        if (conflict != null) {
            body.append("- ⚠️ ").append(conflict).append('\n');
        }
        body.append("- ").append(configLine(dir)).append('\n');
        if (Files.isRegularFile(dir.resolve(ScriptFacts.DESIGN_REL_PATH)) || !snap.written().isEmpty()) {
            body.append("- ").append(designLine(snap.design(), snap.plan())).append('\n');
        }
        if (Files.isRegularFile(dir.resolve(ScriptFacts.CHAPTER_DETAILS_REL_PATH))) {
            body.append("- 章节细节稿：在（").append(ScriptFacts.CHAPTER_DETAILS_REL_PATH)
                    .append("）—— 落盘时的细节以它为准，跟设计稿的梗概有出入就当场对齐\n");
        }
        body.append("- 已落盘：")
                .append(snap.written().isEmpty() ? "（无）" : String.join(" ", snap.written()))
                .append('\n');
        if (!snap.written().isEmpty()) {
            // TODO(P2-7): missing_assets_of_written —— 等项目内素材校验器（validate.check_chapter_assets 的等价物）落地后补
        }
        String after = chapterAfter(snap);
        if (after != null) {
            boolean exists = snap.written().contains(after);
            body.append("- 下一章 ").append(after).append("：")
                    .append(exists ? "磁盘上已有内容（本轮改的是它前面，别顺手覆盖它）" : "尚未落盘")
                    .append('\n');
        }
        String queue = StageQueue.readQueueProgress(dir);
        if (queue != null) {
            body.append("- 队列：").append(queue).append('\n');
        }
        return "\n\n【落盘进度】\n" + body;
    }

    /**
     * 剧本配置那一行。原版读 {@code story_config.yaml} 分三支（缺 / 只有骨架 / 已就绪）；
     * <b>本项目剧本配置入库（{@code interview_script} 表），不在 {@code story_config.yaml}</b>，
     * 所以固定返回这一句（技术栈差异）。
     */
    static String configLine(Path dir) {
        return "剧本配置：在 interview_script 表里（本项目剧本配置入库，不在 story_config.yaml）";
    }

    /**
     * 设计稿那一行：告诉模型代码从设计稿里读出了什么；{@code design} 只回答读没读到（空文件也算读到），
     * 章节列表用事实层解析好的 {@code plan}。
     */
    static String designLine(String design, List<String> plan) {
        if (design == null) {
            return "设计稿：缺（还没有 .agent/design.md）";
        }
        if (plan == null || plan.isEmpty()) {
            return "设计稿：在，但读不出章节 —— 每个 `##` 标题后的**第一个非空行**必须写 `id: 01`"
                    + "（写在别处的 id 会被忽略），补上才能按章推进（改设计稿是「改大纲」那一项的活，"
                    + "本项不是它就把这件事写进回执）";
        }
        return "设计稿：" + plan.size() + " 章（" + String.join(" ", plan) + "）";
    }

    /** 下一章：设计稿里紧接着 {@code next_chapter} 的那一章（{@code prompt.rs:451-455}）。 */
    private static String chapterAfter(StageFacts snap) {
        String current = snap.nextChapter();
        if (current == null) {
            return null;
        }
        int idx = snap.plan().indexOf(current);
        if (idx < 0 || idx + 1 >= snap.plan().size()) {
            return null;
        }
        return snap.plan().get(idx + 1);
    }

    // ───────────────────────── 设计稿解析（evidence.rs:316-372） ─────────────────────────

    /** 设计稿里 id 为 {@code id} 的那一章整块（{@code ## } 起块、块内第一非空行 {@code id:} 取值）。 */
    public static String extractChapterBlock(String markdown, String id) {
        if (markdown == null || id == null) {
            return null;
        }
        List<String> lines = lines(markdown);
        int[][] ranges = blockRanges(lines);
        for (int[] range : ranges) {
            List<String> block = lines.subList(range[0] + 1, range[1]);
            String blockId = blockId(block);
            if (id.equals(blockId)) {
                return trimEnd(String.join("\n", lines.subList(range[0], range[1])));
            }
        }
        return null;
    }

    /** 章节末尾的注释块（{@code SKILL.md}「状态注释原则」要求记录的收尾状态）。 */
    public static String tailState(String chapterText) {
        if (chapterText == null || chapterText.isEmpty()) {
            return "";
        }
        List<String> lines = lines(chapterText);
        List<String> tail = new java.util.ArrayList<>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            String t = line.trim();
            if (t.startsWith("#")) {
                tail.add(line);
            } else if (!t.isEmpty()) {
                break;
            }
        }
        java.util.Collections.reverse(tail);
        return String.join("\n", tail);
    }

    /** 每块 {@code ## } 的 {@code [起始行, 下一块起始行或行数)}。 */
    private static int[][] blockRanges(List<String> lines) {
        List<Integer> starts = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("##")) {
                starts.add(i);
            }
        }
        int[][] out = new int[starts.size()][2];
        for (int n = 0; n < starts.size(); n++) {
            out[n][0] = starts.get(n);
            out[n][1] = n + 1 < starts.size() ? starts.get(n + 1) : lines.size();
        }
        return out;
    }

    /** 块内第一个非空行必须是 {@code id: xx}；带引号的取值去引号。 */
    private static String blockId(List<String> block) {
        for (String line : block) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!t.startsWith("id:")) {
                return null;
            }
            String rest = t.substring(3);
            return trimChars(rest.trim(), "\"'").trim();
        }
        return null;
    }

    /** 读章节正文（原版 {@code read_chapter}）。本项目章节在库里，盘上没有 {@code Chapters/} 时返回空串。 */
    private static String readChapter(Path scriptDir, String id) {
        if (scriptDir == null || id == null) {
            return "";
        }
        Path file = scriptDir.resolve("Chapters").resolve(id + ".yaml");
        String text = readOrNull(file);
        return text == null ? "" : text;
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static String targetAt(List<QueueItem> items, int index) {
        if (index < 0 || index >= items.size()) {
            return "";
        }
        String target = items.get(index).target();
        return target == null ? "" : target.trim();
    }

    /** 依序替换模板里的位置占位符 {@code {}}（Rust {@code format!} 的顺序语义）。 */
    private static String fill(String template, String arg) {
        int index = template.indexOf("{}");
        if (index < 0) {
            return template;
        }
        return template.substring(0, index) + arg + template.substring(index + 2);
    }

    /** Rust {@code trim_end()}：去掉末尾的空白（含换行）。 */
    static String trimEnd(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** Rust {@code str::lines()}：按 {@code \n} 切、去掉行尾 {@code \r}，末尾换行不产生空行。 */
    private static List<String> lines(String text) {
        List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                String line = text.substring(start, i);
                out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                start = i + 1;
            }
        }
        if (start < text.length()) {
            String line = text.substring(start);
            out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return out;
    }

    private static String trimChars(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    private static String readOrNull(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String read(String resource) {
        try (InputStream in = StagePrompt.class.getResourceAsStream(resource)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}

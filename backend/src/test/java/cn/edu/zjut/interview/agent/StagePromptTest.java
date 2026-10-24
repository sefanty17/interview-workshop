package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.QueueItem;
import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StagePrompt} / {@link ScriptBlock} 的逐段对齐测试（规格
 * {@code .workbuddy/plans/P1规格-taskblock与run-materials.md} 验收第 2、4 条）。
 *
 * <p>每条断言都对应用原版的一行：{@code prompt.rs::build_task_block}（63-222）、
 * {@code handoff_digest}（228-264）、{@code deferred_question}（267-274）、
 * {@code build_run_materials}（291-321）、{@code history.rs::build_script_block}（10-36）。
 */
class StagePromptTest {

    /** 五段任务块模板都必须读得到（防 classpath 路径写错：读不到就是空串）。 */
    @Test
    void fiveTaskBlockTemplatesAreReadable() {
        for (String name : List.of("stage_build_task_block", "stage_build_task_block_2",
                "stage_build_task_block_3", "stage_build_task_block_4", "stage_build_task_block_5")) {
            String text = readResource("/agent/prompts/" + name + ".md");
            assertNotNull(text, name + " 应在 classpath 上");
            assertFalse(text.isEmpty(), name + " 不能是空串");
        }
        // 模板内容真的进了任务块（不是只读到了没用上）
        String single = StagePrompt.buildTaskBlock(input(TaskKind.CHAT, "", List.of(item(TaskKind.CHAT, "")),
                0, true, false));
        assertTrue(single.contains("【对不上用户的意思就别做】"), "block_2 已拼进任务块：" + single);
        assertTrue(single.contains("【听用户的】你这一轮能用的工具是 。"), "block_3 的 {} 填了 allowed：" + single);
        assertTrue(single.contains("（本轮不注入任何角色指令。）"), "block_4 之后是「不注入手册」那一句");
        assertTrue(single.contains("（本轮不注入任何角色指令。）"), "materials 空照发原版那一句");
    }

    /** 单项目标为空 → 不出现「｜本项目标」；非空 → 出现在标签之后（{@code prompt.rs:81-88}）。 */
    @Test
    void targetAppearsOnlyWhenPresent() {
        List<QueueItem> one = List.of(item(TaskKind.OUTLINE, ""));
        String noTarget = StagePrompt.buildTaskBlock(input(TaskKind.OUTLINE, "", one, 0, true, false));
        assertTrue(noTarget.startsWith("\n\n【本轮任务】编写大纲（编剧）"), "开头：" + noTarget.substring(0, 40));
        assertFalse(noTarget.contains("｜本项目标"), "目标为空时整段不出现");

        List<QueueItem> withTarget = List.of(item(TaskKind.OUTLINE, "02"));
        String hasTarget = StagePrompt.buildTaskBlock(input(TaskKind.OUTLINE, "02", withTarget, 0, true, false));
        assertTrue(hasTarget.contains("【本轮任务】编写大纲（编剧）｜本项目标：02"), "有目标：" + hasTarget);
        assertFalse(hasTarget.contains("队列第"), "单项不出现队列行");
    }

    /** 多项队列：`　队列第 2/3 项，只做这一项`（全角空格起头，{@code prompt.rs:93-104}）。 */
    @Test
    void queueLineUsesFullWidthSpaceAndIndex() {
        List<QueueItem> three = new ArrayList<>(List.of(
                item(TaskKind.OUTLINE, ""),
                item(TaskKind.DRAFT_CHAPTER, "03"),
                item(TaskKind.WRITE_CHAPTER, "03")));
        String block = StagePrompt.buildTaskBlock(input(TaskKind.DRAFT_CHAPTER, "03", three, 1, false, false));
        assertTrue(block.contains("｜本项目标：03　队列第 2/3 项，只做这一项"),
                "队列行必须紧跟本项目标、以全角空格起头：" + block.substring(0, 60));
        assertTrue(block.contains("【本轮共 3 项，这是第 2 项】"), "多项时追加共几项：" + block);
    }

    /** {@code isLast=true}：有「【本轮最后一项】」，且不出现中间项才有的 block_5 原文。 */
    @Test
    void lastItemAddsLastItemNoteAndSkipsBlock5() {
        List<QueueItem> one = List.of(item(TaskKind.CHAT, ""));
        // 参数顺序是 (…, land, isLast)：最后一项要 isLast=true
        String block = StagePrompt.buildTaskBlock(input(TaskKind.CHAT, "", one, 0, false, true));
        assertTrue(block.contains("\n【本轮最后一项】你是本轮最后执行的一项：先把这一项交待完"),
                "缺【本轮最后一项】：" + block);
        assertTrue(block.contains("你是本轮最后一项：用户要定的事在这里一次说清"), "回执段也要换上收尾版");
        assertFalse(block.contains(StagePrompt.DEFERRED_MARK), "最后一项不写【留给最后一项问】");
    }

    /** {@code isLast=false}：出现 {@link StagePrompt#DEFERRED_MARK}，且不出现「【本轮最后一项】」。 */
    @Test
    void middleItemAsksForDeferralInstead() {
        List<QueueItem> two = List.of(item(TaskKind.OUTLINE, ""), item(TaskKind.WRITE_CHAPTER, "01"));
        String block = StagePrompt.buildTaskBlock(input(TaskKind.OUTLINE, "", two, 0, false, false));
        assertTrue(block.contains("**你是中间项：不要向用户提问**"), "缺 block_5：" + block);
        assertTrue(block.contains("`" + StagePrompt.DEFERRED_MARK + "<一句话说清要他定什么>`"),
                "block_5 的 {} 填了 DEFERRED_MARK：" + block);
        assertFalse(block.contains("【本轮最后一项】"), "中间项没有最后一项那一段");
    }

    /** 用户原话、职责边界、承接说明的位置与措辞（{@code prompt.rs:137-210}）。 */
    @Test
    void userWordsBoundaryAndHandoffAreInPlace() {
        ScriptFacts facts = ScriptFacts.collect(null, "05", List.of());
        ScriptFacts.Handoff handoff = ScriptFacts.reconcile(TaskKind.REVISE_OUTLINE, facts);
        List<QueueItem> one = List.of(item(TaskKind.REVISE_OUTLINE, "05"));
        StagePrompt.TaskBlockInput in = new StagePrompt.TaskBlockInput(TaskKind.REVISE_OUTLINE, handoff,
                one, 0, "  把第 5 章改成雨天  ", null, false, true, List.of(), List.of("list_files"));
        String block = StagePrompt.buildTaskBlock(in);
        assertTrue(block.contains("\n【用户这一轮的原话】把第 5 章改成雨天"), "用户原话要 trim：" + block);
        assertTrue(block.contains("\n【职责边界】"), "缺职责边界段");
        assertTrue(block.contains("\n【承接说明】本轮实际要先做「编写大纲」"), "REVISE_OUTLINE 无设计稿 → PREREQUISITE：" + block);
        // PREREQUISITE 时队列行换成「先补前置（见下）」——但这里只有 1 项，队列行不出现
        // 原版 prompt.rs:105-118 只看 handoff 是不是 Prerequisite，不看队列几项
        assertTrue(block.contains("【这一项为什么换了名字】原定这一项是「改大纲：05」；"), "PREREQUISITE 单项也有换名字段：" + block);
        assertFalse(block.contains("队列第 2/"), "单项没有队列第几项那一段");
    }

    /** PREREQUISITE 在多项队列里：换名字段逐字（{@code prompt.rs:105-119}）。 */
    @Test
    void prerequisiteExplainsRename() {
        ScriptFacts facts = ScriptFacts.collect(null, "01", List.of());
        ScriptFacts.Handoff handoff = ScriptFacts.reconcile(TaskKind.WRITE_CHAPTER, facts);
        assertEquals(ScriptFacts.Handoff.Kind.PREREQUISITE, handoff.kind(), "无细节稿 → 先写正文");
        List<QueueItem> two = List.of(item(TaskKind.WRITE_CHAPTER, "01"), item(TaskKind.POLISH, ""));
        StagePrompt.TaskBlockInput in = new StagePrompt.TaskBlockInput(TaskKind.DRAFT_CHAPTER, handoff,
                two, 0, "把第一章落盘", null, true, false, List.of(), List.of("list_files"));
        String block = StagePrompt.buildTaskBlock(in);
        assertTrue(block.contains("队列第 1/2 项，先补前置（见下）"), "先补前置：" + block.substring(0, 80));
        assertTrue(block.contains("【这一项为什么换了名字】原定这一项是「转成 YAML：01」；"), "换名字段：" + block);
        assertTrue(block.contains("所以本轮先做前置的「写正文」。同轮会接着做回原任务（见【承接说明】）。"));
        assertFalse(block.contains("【本轮不产出能跑的章节】"), "land=true 时不贴 dictate_note");
    }

    /**
     * {@code role_dictate_note.md} 的触发条件（{@code prompt.rs:145-148}）：
     * {@code !land && !task.materials().is_empty() && task.role().is_some()}。
     *
     * <p><b>本项目不贴</b>：{@code materialsOf} 恒为空（角色手册由 system 注入），
     * 所以这个条件永远为假 —— 原版 {@code Chat} 的 {@code materials()} 也是空表，同样不贴。
     * 这条测试锁住的是「三个条件里的 materials 那一支」，不是"想不贴就不贴"。
     */
    @Test
    void dictateNoteNeverAppearsBecauseMaterialsIsEmptyHere() {
        List<QueueItem> one = List.of(item(TaskKind.DRAFT_CHAPTER, "02"));
        assertTrue(StagePrompt.materialsOf(TaskKind.DRAFT_CHAPTER).isEmpty(), "本项目任务块不预注入手册");
        String noLand = StagePrompt.buildTaskBlock(input(TaskKind.DRAFT_CHAPTER, "02", one, 0, false, true));
        assertFalse(noLand.contains("【本轮不产出能跑的章节】"), "materials 为空 → 不贴 dictate_note：" + noLand);
        String chat = StagePrompt.buildTaskBlock(input(TaskKind.CHAT, "", List.of(item(TaskKind.CHAT, "")),
                0, false, true));
        assertFalse(chat.contains("【本轮不产出能跑的章节】"), "Chat 无角色，不贴 dictate_note");
        assertTrue(chat.contains("（不涉及剧本）"), "Chat 的标签括号是「（不涉及剧本）」");
    }

    /** 跳过项那一段（{@code prompt.rs:120-125}）。 */
    @Test
    void skippedItemsAreListed() {
        StagePrompt.TaskBlockInput in = new StagePrompt.TaskBlockInput(TaskKind.POLISH, ScriptFacts.Handoff.PROCEED,
                List.of(item(TaskKind.POLISH, "")), 0, "", null, false, true,
                List.of("改第 9 章：盘上没有", "转成 YAML：10：没写正文"), List.of());
        String block = StagePrompt.buildTaskBlock(in);
        assertTrue(block.contains("\n【队列里做不了的项】改第 9 章：盘上没有；转成 YAML：10：没写正文"
                + "（在回执里逐条说明为什么，别装没看见）"), "跳过项用「；」join：" + block);
    }

    /** {@link StagePrompt#deferredQuestion}：从回执里摘一行；没标记 → null；标记后为空 → null。 */
    @Test
    void deferredQuestionExtractsOnlyMarkedLine() {
        String reply = "回执正文第一行\n  " + StagePrompt.DEFERRED_MARK + "  第 3 章要不要现在落盘？  \n末尾";
        assertEquals("第 3 章要不要现在落盘？", StagePrompt.deferredQuestion(reply));
        assertNull(StagePrompt.deferredQuestion("回执里什么都没写"));
        assertNull(StagePrompt.deferredQuestion(""));
        assertNull(StagePrompt.deferredQuestion(null));
        assertNull(StagePrompt.deferredQuestion(StagePrompt.DEFERRED_MARK), "标记后没有内容不算一条");
        // 第一行命中优先（不往后找第二条）
        assertEquals("甲", StagePrompt.deferredQuestion(StagePrompt.DEFERRED_MARK + "甲\n"
                + StagePrompt.DEFERRED_MARK + "乙"));
    }

    /** {@code handoffDigest(upto=0, deferred=[])} → 空串；只有 deferred 时只有待问那一段。 */
    @Test
    void handoffDigestEmptyOnFirstItemAndOnlyDeferredSection(@TempDir Path dir) throws Exception {
        assertEquals("", StagePrompt.handoffDigest(dir, List.of(item(TaskKind.CHAT, "")), 0, List.of()));
        assertEquals("", StagePrompt.handoffDigest(dir, List.of(item(TaskKind.CHAT, "")), 0, new ArrayList<>()));

        String onlyDeferred = StagePrompt.handoffDigest(dir, List.of(item(TaskKind.CHAT, "")), 0,
                List.of("要不要改成「只用已有」？"));
        assertEquals("\n【前面各项留给最后一项问用户的事】\n- 要不要改成「只用已有」？", onlyDeferred,
                "只有 deferred 时整段从「【前面各项留给最后一项问用户的事】」起头");
    }

    /** 进度清单读 {@code queue.json} 的账；查不到 → 「没有可核对的产物（…）」（{@code prompt.rs:240-252}）。 */
    @Test
    void handoffDigestReadsMarksFromQueue(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".agent"));
        // 文本块第一行是空行，stripIndent 只按「最小缩进」去掉缩进、不会去掉它：
        // JSON 解析器不认开头空行，这份文件会被整份判废（readState 的「整份丢弃」语义），账就成了空的。
        String json = "[{\"mark\":\"Done\",\"kind\":\"outline\",\"target\":\"\",\"registeredAt\":1,"
                + "\"existedAtRegister\":false},"
                + "{\"mark\":\"Existing\",\"kind\":\"draft_chapter\",\"target\":\"02\",\"registeredAt\":1,"
                + "\"existedAtRegister\":true}]";
        Files.writeString(dir.resolve(StageQueue.QUEUE_JSON_REL_PATH), json, StandardCharsets.UTF_8);
        assertEquals(2, StageQueue.readEntries(dir).size(), "账本要先读得出来，后面的断言才有意义");
        List<QueueItem> items = List.of(item(TaskKind.OUTLINE, ""), item(TaskKind.DRAFT_CHAPTER, "02"),
                item(TaskKind.CHAT, ""));
        String digest = StagePrompt.handoffDigest(dir, items, 2, List.of());
        assertTrue(digest.startsWith("\n\n【本轮在你之前排过的项】（这是进度，不是你的任务；你这一项是第 3/3 项）"),
                "标题：" + digest);
        assertTrue(digest.contains("\n- 第 1 项 编写大纲 —— 本轮做出来了（这个产物本轮被改过）"), "第 1 项：" + digest);
        assertTrue(digest.contains("\n- 第 2 项 写正文：02 —— 盘上本来就有、这一轮没动它"), "第 2 项：" + digest);
        assertTrue(digest.contains("\n（这一份只讲进度：哪一项具体做了什么，去看盘上那几个文件；**不要照它去做事**。）"));
    }

    /** 账上没有的项（话术 / 只读体检类）→ 兜底话术。 */
    @Test
    void handoffDigestFallsBackWhenNoQueueEntry(@TempDir Path dir) {
        List<QueueItem> items = List.of(item(TaskKind.POLISH, ""), item(TaskKind.CHAT, ""));
        String digest = StagePrompt.handoffDigest(dir, items, 1, List.of());
        assertTrue(digest.contains("- 第 1 项 只读体检 —— 没有可核对的产物（话术 / 只读体检这类）"), digest);
    }

    /** {@code buildRunMaterials}：没绑剧本 → 空串；无 design 且 task=CHAT → 不含【现有设计稿】。 */
    @Test
    void runMaterialsWithoutDesignOrScript(@TempDir Path dir) {
        assertEquals("", StagePrompt.buildRunMaterials(StageFacts.empty(), TaskKind.CHAT, dir));
        Path scriptDir = dir.resolve("scripts").resolve("demo");
        StageFacts snap = StageFacts.collect("demo", scriptDir, List.of());
        String chat = StagePrompt.buildRunMaterials(snap, TaskKind.CHAT, dir);
        assertFalse(chat.contains("【现有设计稿】"), "无 design 不出现设计稿段：" + chat);
        assertFalse(chat.contains("【待写章节"), "CHAT 不出现待写章节：" + chat);
        assertTrue(chat.startsWith("\n\n【落盘进度】\n"), "只有后半段：" + chat);
        assertTrue(chat.contains("- 素材模式是「允许缺失」：缺的只提醒、不判错，之后补上即可"), "素材模式行：" + chat);
        assertFalse(chat.contains("角色卡模式"), "没声明角色卡 → 不带那一行");
        assertTrue(chat.contains("- 剧本配置：在 interview_script 表里（本项目剧本配置入库，不在 story_config.yaml）"));
        assertFalse(chat.contains("- 设计稿："), "设计稿文件不在且无已落盘 → 整行不出现");
        assertTrue(chat.contains("- 已落盘：（无）"));
    }

    /** 有 design 且 task=OUTLINE → 含【现有设计稿】；WRITE_CHAPTER → 待写章节 + 上一章收尾。 */
    @Test
    void runMaterialsDesignAndForgeBranches(@TempDir Path dir) throws Exception {
        Path scriptDir = dir.resolve("scripts").resolve("demo");
        Files.createDirectories(scriptDir.resolve(".agent"));
        Files.writeString(scriptDir.resolve(".agent").resolve("design.md"), """
                # 设计稿

                ## 第一章
                id: 01
                开场

                ## 第二章
                id: 02
                转折""".stripIndent(), StandardCharsets.UTF_8);
        Files.createDirectories(scriptDir.resolve("Chapters"));
        Files.writeString(scriptDir.resolve("Chapters").resolve("01.yaml"), """
                name: 一
                events: []
                # 状态
                # 结尾：线性到 02""".stripIndent(), StandardCharsets.UTF_8);
        StageFacts snap = StageFacts.collect("demo", scriptDir, List.of("01"));

        String outline = StagePrompt.buildRunMaterials(snap, TaskKind.OUTLINE, dir);
        assertTrue(outline.contains("\n\n【现有设计稿】\n# 设计稿"), "OUTLINE 带设计稿全文：" + outline);
        assertTrue(outline.contains("- 设计稿：2 章（01 02）"), "设计稿行：" + outline);
        assertTrue(outline.contains("- 已落盘：01"));

        String forge = StagePrompt.buildRunMaterials(snap, TaskKind.WRITE_CHAPTER, dir);
        assertFalse(forge.contains("【现有设计稿】"), "WRITE_CHAPTER 不带设计稿全文");
        assertTrue(forge.contains("\n\n【待写章节 · 02】\n## 第二章\nid: 02\n转折"), "待写章节整块：" + forge);
        assertTrue(forge.contains("\n\n【上一章（01）收尾状态】\n# 状态\n# 结尾：线性到 02"), "上一章收尾：" + forge);
        // design 里有 01/02，written=01 → next_chapter=02 是最后一章，后面没有第三章
        // → 原版 chapter_after 返回 None，没有「下一章」这一行（prompt.rs:466-471）
        assertFalse(forge.contains("- 下一章 "), "设计稿只剩最后一章 → 不出现下一章行：" + forge);

        String chat = StagePrompt.buildRunMaterials(snap, TaskKind.CHAT, dir);
        // CHAT 在原版里属于 Setup / Modify 阶段（核心对话那一档）→ 有设计稿就整份注入（prompt.rs:296-299）
        assertTrue(chat.contains("【现有设计稿】"), "CHAT 有设计稿时要注入全文：" + chat);
        // 原版 chapter_after 只在当前项是"下一个待写章"时才有值：这一轮在写 02，
        // 02 是 next_chapter，但它后面没有第三篇 → 没有「下一章」这一行（prompt.rs:466-471）
        assertFalse(chat.contains("下一章"), "next 之后没有章 → 不出现下一章行：" + chat);
    }

    /** 设计稿在但读不出章节 → 那一行的格式提醒逐字（{@code prompt.rs:428-432}）。 */
    @Test
    void designLineReminderWhenPlanUnreadable(@TempDir Path dir) {
        String design = "# 只有标题，没有 id 行\n";
        String line = StagePrompt.designLine(design, List.of());
        assertTrue(line.startsWith("设计稿：在，但读不出章节 —— 每个 `##` 标题后的**第一个非空行**必须写 `id: 01`"));
        assertEquals("设计稿：缺（还没有 .agent/design.md）", StagePrompt.designLine(null, List.of()));
    }

    /** 素材模式声明的两行都上（素材 + 角色卡）+ 矛盾提示（{@code prompt.rs:331-343}）。 */
    @Test
    void progressBlockModesAndConflict(@TempDir Path dir) throws Exception {
        Path scriptDir = dir.resolve("scripts").resolve("demo");
        Files.createDirectories(scriptDir.resolve(".agent"));
        Files.writeString(scriptDir.resolve(".agent").resolve("constraints.md"),
                "- 素材模式：只用已有\n- 角色卡模式：先预留\n", StandardCharsets.UTF_8);
        StageFacts snap = StageFacts.collect("demo", scriptDir, List.of());
        String block = StagePrompt.buildRunMaterials(snap, TaskKind.CHAT, dir);
        assertTrue(block.contains("- 素材模式是「只用已有」：引用磁盘上没有的就是错，当场改"
                + "（`.agent/constraints.md` 是唯一来源；要改就改那一行，并检查别处有没有旧说法）"), block);
        assertTrue(block.contains("- 角色卡模式是「允许缺失」：缺的只提醒、不判错，之后补上即可（同上，改一处就全都对齐）"),
                block);

        Files.writeString(scriptDir.resolve(".agent").resolve("constraints.md"),
                "- 素材模式：只用已有\n- 素材来源：允许缺失\n", StandardCharsets.UTF_8);
        String conflict = StagePrompt.buildRunMaterials(StageFacts.collect("demo", scriptDir, List.of()),
                TaskKind.CHAT, dir);
        assertTrue(conflict.contains("⚠️ 卡片里「素材模式」有两处互相矛盾的说法（OnlyExisting 与 AllowMissing），我按最后一条走"),
                conflict);
    }

    /** {@link ScriptBlock}：无 key 走模板 + 现有剧本包列表；有 key 走「【当前剧本上下文】」。 */
    @Test
    void scriptBlockBothBranches(@TempDir Path dir) throws Exception {
        String noKey = ScriptBlock.build(dir, null, List.of("demo", "character/阿绫/雨夜"));
        assertTrue(noKey.startsWith("【当前剧本上下文】"), "模板首行：" + noKey.substring(0, 30));
        assertTrue(noKey.contains("本会话**还没有绑定剧本**"));
        assertTrue(noKey.contains("\n\n（磁盘上现有的剧本包，仅供你告诉用户「有这些」：demo、character/阿绫/雨夜）"));
        assertTrue(noKey.contains("<standalone 或 character/<角色>>/<剧本名>/story_config.yaml"),
                "路径口径保持原版不改：" + noKey);
        assertFalse(ScriptBlock.build(dir, "  ", List.of()).contains("磁盘上现有的剧本包"), "空 key + 空表 → 只有模板");

        Path scriptDir = dir.resolve("scripts").resolve("demo");
        Files.createDirectories(scriptDir);
        String withKey = ScriptBlock.build(dir, "demo", List.of("demo"));
        assertTrue(withKey.startsWith("\n\n【当前剧本上下文】\n剧本 key：demo\n剧本目录：scripts/demo（相对于文件沙箱根 "),
                "有 key 的段：" + withKey);
        assertTrue(withKey.contains("（如 夜晚.webp）"));
        assertFalse(withKey.contains("磁盘上现有的剧本包"), "有 key 时不列剧本包");

        assertEquals("", ScriptBlock.build(dir, "missing", List.of()), "目录不存在 → 空串，不阻断");
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private static QueueItem item(TaskKind kind, String target) {
        return new QueueItem(kind, target);
    }

    private static StagePrompt.TaskBlockInput input(TaskKind kind, String target, List<QueueItem> items,
                                                   int index, boolean land, boolean isLast) {
        return new StagePrompt.TaskBlockInput(kind, ScriptFacts.Handoff.PROCEED, items, index,
                "用户这一句", null, land, isLast, List.of(), SkillAgentRouter.toolsFor(kind));
    }

    private static String readResource(String resource) {
        try (var in = StagePromptTest.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}

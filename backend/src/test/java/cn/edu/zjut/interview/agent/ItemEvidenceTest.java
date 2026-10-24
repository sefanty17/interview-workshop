package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-6：【代码核对】的物证判定（规格
 * {@code .workbuddy/plans/P0-6规格-代码核对二跑.md} 第 3 条）。
 *
 * <p>关键用例是"文件本来就在且没改 → false"：按"存在"判定会放行空跑，
 * 这是原版 {@code existed_at_register} 存在的唯一目的。
 */
class ItemEvidenceTest {

    private static final String KEY = "demo";

    @TempDir
    Path sandbox;

    /** 剧本包目录：一律走唯一的 {@link SkillAgentService#scriptDir} 推导，测试里不自己拼路径。 */
    private Path scriptDir(String key) {
        return SkillAgentService.scriptDir(sandbox, key);
    }

    /** 沙箱里某个剧本包下的文件。 */
    private Path agentFile(String relative) {
        return scriptDir(KEY).resolve(relative).normalize();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    // ───────────────────────── judgeable（照抄 queue.rs:170-178） ─────────────────────────

    @Test
    void judgeableFollowsQueueRules() {
        assertTrue(ItemEvidence.judgeable(TaskKind.WRITE_CHAPTER, "03"), "章节类有 target → 可核对");
        assertFalse(ItemEvidence.judgeable(TaskKind.WRITE_CHAPTER, ""), "章节类空 target → 不核对");
        assertFalse(ItemEvidence.judgeable(TaskKind.WRITE_CHAPTER, "   "), "空白 target 也不算");
        assertFalse(ItemEvidence.judgeable(TaskKind.CHAT, "03"), "纯对话没有可核对产物");
        assertFalse(ItemEvidence.judgeable(TaskKind.COLLECT, "03"));
        assertFalse(ItemEvidence.judgeable(TaskKind.SUMMARIZE, ""));
        assertFalse(ItemEvidence.judgeable(TaskKind.POLISH, "03"));
        assertTrue(ItemEvidence.judgeable(TaskKind.DRAFT_CHAPTER, "03"), "细节稿章节类要 target");
        assertTrue(ItemEvidence.judgeable(TaskKind.OUTLINE, ""), "大纲恒可核对");
        assertTrue(ItemEvidence.judgeable(TaskKind.REVISE_OUTLINE, ""));
        assertTrue(ItemEvidence.judgeable(TaskKind.CHECK_ASSETS, ""));
    }

    // ───────────────────────── produced：盘上文件 ─────────────────────────

    @Test
    void draftChapterAppearsAfterWrite() throws IOException {
        Path file = agentFile(ScriptFacts.CHAPTER_DETAILS_REL_PATH);
        ChapterLander lander = mock(ChapterLander.class);
        when(lander.writtenChapterIds(KEY)).thenReturn(java.util.List.of());

        ItemEvidence.Snapshot before =
                ItemEvidence.capture(TaskKind.DRAFT_CHAPTER, "03", scriptDir(KEY), KEY, lander);
        assertFalse(before.exists(), "还没写，快照是空的");

        write(file, "## 第3章 · 面试开场\n\n面试官：请做一下自我介绍。\n");

        assertTrue(ItemEvidence.produced(TaskKind.DRAFT_CHAPTER, "03", before, scriptDir(KEY), KEY, lander),
                "从无到有 → 做出来了");
    }

    @Test
    void existingFileUntouchedIsNotProduced() throws IOException {
        Path file = agentFile(ScriptFacts.DESIGN_REL_PATH);
        write(file, "## 第1章 · id: 01\n");
        // mtime 固定在很久以前：模拟"文件本来就在、这一项一个字没动"
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));

        ItemEvidence.Snapshot before =
                ItemEvidence.capture(TaskKind.OUTLINE, "", scriptDir(KEY), KEY, mock(ChapterLander.class));

        assertFalse(ItemEvidence.produced(TaskKind.OUTLINE, "", before, scriptDir(KEY), KEY, mock(ChapterLander.class)),
                "文件本来就在、没被改过 → 不算做完（防「存在即放行」）");
    }

    @Test
    void existingFileEditedIsProduced() throws IOException {
        Path file = agentFile(ScriptFacts.DESIGN_REL_PATH);
        write(file, "## 第1章 · id: 01\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));
        ChapterLander lander = mock(ChapterLander.class);

        ItemEvidence.Snapshot before = ItemEvidence.capture(TaskKind.OUTLINE, "", scriptDir(KEY), KEY, lander);

        write(file, "## 第1章 · id: 01\n## 第2章 · id: 02\n");

        assertTrue(ItemEvidence.produced(TaskKind.OUTLINE, "", before, scriptDir(KEY), KEY, lander),
                "内容改了 → 做出来了");
    }

    @Test
    void sameSizeSameMtimeButDifferentContentStillCounts() throws IOException {
        Path file = agentFile(ItemEvidence.ASSETS_REL_PATH);
        write(file, "# 素材缺口表\n-「咖啡馆背景」\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));
        ChapterLander lander = mock(ChapterLander.class);

        ItemEvidence.Snapshot before = ItemEvidence.capture(TaskKind.CHECK_ASSETS, "", scriptDir(KEY), KEY, lander);

        write(file, "# 素材缺口表\n-「面试室背景」\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_000_000L));

        assertTrue(ItemEvidence.produced(TaskKind.CHECK_ASSETS, "", before, scriptDir(KEY), KEY, lander),
                "同大小同 mtime 但摘要不同，也要认出来改过");
    }

    @Test
    void draftChapterWithoutTargetSectionIsNotProduced() throws IOException {
        Path file = agentFile(ScriptFacts.CHAPTER_DETAILS_REL_PATH);
        ChapterLander lander = mock(ChapterLander.class);
        ItemEvidence.Snapshot before = ItemEvidence.capture(TaskKind.DRAFT_CHAPTER, "03", scriptDir(KEY), KEY, lander);

        write(file, "## 第1章 · 开场\n\n随便写了点别的。\n");

        assertFalse(ItemEvidence.produced(TaskKind.DRAFT_CHAPTER, "03", before, scriptDir(KEY), KEY, lander),
                "稿子里没有第 3 章的小节 → 没做出来");
    }

    // ───────────────────────── produced：章节（原版口径 = Chapters/ 下的文件） ─────────────────────────

    @Test
    void writeChapterCountsOnlyWhenYamlAppearsOnDisk() throws Exception {
        ChapterLander lander = mock(ChapterLander.class);
        ItemEvidence.Snapshot before =
                ItemEvidence.capture(TaskKind.WRITE_CHAPTER, "03", scriptDir(KEY), KEY, lander);
        assertFalse(before.exists());
        assertFalse(ItemEvidence.produced(TaskKind.WRITE_CHAPTER, "03", before, scriptDir(KEY), KEY, lander),
                "盘上还没有 03 → 没做出来");

        writeChapterYaml("03");
        assertTrue(ItemEvidence.produced(TaskKind.WRITE_CHAPTER, "03", before, scriptDir(KEY), KEY, lander),
                "Chapters/03.yaml 出现 → 做出来了");
    }

    @Test
    void reviseChapterCountsAppearOrDisappear() throws Exception {
        ChapterLander lander = mock(ChapterLander.class);
        writeChapterYaml("03");
        ItemEvidence.Snapshot present =
                ItemEvidence.capture(TaskKind.REVISE_CHAPTER, "03", scriptDir(KEY), KEY, lander);
        assertTrue(present.exists(), "改之前盘上有这一章");

        // 删掉了：从有到无也算做出来了
        Files.delete(scriptDir(KEY).resolve("Chapters/03.yaml"));
        assertTrue(ItemEvidence.produced(TaskKind.REVISE_CHAPTER, "03", present, scriptDir(KEY), KEY, lander),
                "删除类：真的不见了 → 做出来了");

        // 本来就没有、也没有：状态没变 → 没做
        ItemEvidence.Snapshot absent =
                ItemEvidence.capture(TaskKind.REVISE_CHAPTER, "03", scriptDir(KEY), KEY, lander);
        assertFalse(absent.exists());
        assertFalse(ItemEvidence.produced(TaskKind.REVISE_CHAPTER, "03", absent, scriptDir(KEY), KEY, lander),
                "有没变、没也没变 → 没做事");
    }

    @Test
    void artifactExistsIgnoresDatabaseRows() throws Exception {
        ChapterLander lander = mock(ChapterLander.class);
        // 库里（lander）说有 03，盘上没有：种子数据不是"已落盘"
        when(lander.writtenChapterIds(KEY)).thenReturn(java.util.List.of("03"));
        assertFalse(ItemEvidence.artifactExists(scriptDir(KEY), TaskKind.WRITE_CHAPTER, "03", lander),
                "盘上没有 YAML → 不算已完成（否则模型会认定它已存在、一次都不写）");

        writeChapterYaml("03");
        assertTrue(ItemEvidence.artifactExists(scriptDir(KEY), TaskKind.WRITE_CHAPTER, "03", lander));
    }

    private void writeChapterYaml(String id) throws IOException {
        Path dir = scriptDir(KEY).resolve("Chapters");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".yaml"), "name: 第" + id + "章\nevents: []\n");
    }

    // ───────────────────────── 稿子只认剧本包内，不认沙箱根（P0-7） ─────────────────────────

    @Test
    void draftWrittenUnderSandboxRootDoesNotCount() throws IOException {
        ChapterLander lander = mock(ChapterLander.class);
        ItemEvidence.Snapshot before = ItemEvidence.capture(TaskKind.DRAFT_CHAPTER, "01", scriptDir(KEY), KEY, lander);

        // 模型把稿子写错地方：落在沙箱根下，而不是剧本包内
        Path wrong = sandbox.resolve(ScriptFacts.CHAPTER_DETAILS_REL_PATH);
        write(wrong, "## 第1章 · 开场\n\n面试官：请做一下自我介绍。\n");

        assertFalse(ItemEvidence.produced(TaskKind.DRAFT_CHAPTER, "01", before, scriptDir(KEY), KEY, lander),
                "沙箱根下的 .agent/ 不是剧本包产物，不当成做出来了（否则会漏判真没写的项）");
    }

    @Test
    void draftWrittenInsidePackageCounts() throws IOException {
        ChapterLander lander = mock(ChapterLander.class);
        ItemEvidence.Snapshot before = ItemEvidence.capture(TaskKind.DRAFT_CHAPTER, "01", scriptDir(KEY), KEY, lander);

        // 原版口径：.agent/ 在剧本包内
        Path file = agentFile(ScriptFacts.CHAPTER_DETAILS_REL_PATH);
        write(file, "## 第1章 · 开场\n\n面试官：请做一下自我介绍。\n");

        assertTrue(ItemEvidence.produced(TaskKind.DRAFT_CHAPTER, "01", before, scriptDir(KEY), KEY, lander),
                "<sandbox>/scripts/<key>/.agent/chapter-details.md 写了 → 做出来了");
    }

    // ───────────────────────── scriptDir：唯一的剧本包目录推导（P0-7） ─────────────────────────

    @Test
    void scriptDirIsPackageDirAndNullWithoutKey() {
        assertEquals(sandbox.resolve("scripts").resolve(KEY).normalize(), scriptDir(KEY));
        assertNull(SkillAgentService.scriptDir(sandbox, null), "未绑定剧本 → 没有剧本包目录");
        assertNull(SkillAgentService.scriptDir(sandbox, "  "), "key 空白 → 没有剧本包目录");
        assertNull(SkillAgentService.scriptDir(null, KEY), "没有沙箱 → 没有剧本包目录");
    }

    // ───────────────────────── 章节号归一 ─────────────────────────

    @Test
    void chapterKeyNormalizes() {
        assertEquals("3", ItemEvidence.chapterKey("第 3 章"));
        assertEquals("3", ItemEvidence.chapterKey("03"));
        assertEquals("3", ItemEvidence.chapterKey("## 第03章 · 面试开场"));
        assertEquals("", ItemEvidence.chapterKey("end"));
        assertEquals("", ItemEvidence.chapterKey(null));
    }
}

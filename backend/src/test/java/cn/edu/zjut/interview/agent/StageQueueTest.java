package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.QueueItem;
import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5：队列账本（1:1 迁移自 {@code stage/queue.rs}）。
 *
 * <p>钉死的五处语义：①「本轮的」= 登记时刻相等；②<b>本轮第一次记账只登记不下结论</b>
 * （对齐 {@code core.rs:153}：这时还没跑工具，提前判会把"本来就在"的项直接了结 —— 这个 bug 实测必现）；
 * ③之后的记账按盘上事实打勾（{@code Done} / {@code Existing}）；④上一轮的 {@code Open} 出队改判
 * {@code Dropped}；⑤归档去重只看 {@code (kind, target, mark)}，视图的勾与时刻格式与原版一致。
 *
 * <p>章节类不参与本测试（本章节在库里，需要 lander，另有 {@code ItemEvidenceTest} 覆盖）；
 * 这里用大纲与素材盘点 —— 这两类的产物都是文件。
 */
class StageQueueTest {

    @TempDir
    Path scriptDir;

    private Path agentDir;
    private Path design;
    private Path assets;

    @BeforeEach
    void setUp() throws Exception {
        agentDir = scriptDir.resolve(".agent");
        Files.createDirectories(agentDir);
        design = agentDir.resolve("design.md");
        assets = agentDir.resolve("assets.md");
    }

    @Test
    void firstWriteOnlyRegistersSubsequentWriteMarksDone() throws Exception {
        Instant turn = Instant.now().minusSeconds(2);
        at(scriptDir, turn, TaskKind.OUTLINE);

        // 登记那一刻 design.md 还不存在 → Open，且这次记账不下结论
        List<StageQueue.Entry> state = readEntries("queue.json");
        assertEquals(1, state.size(), "第一次记账只登记：" + state);
        assertEquals(StageQueue.Mark.OPEN, state.get(0).mark());
        assertFalse(state.get(0).existedAtRegister(), "登记那一刻盘上没有");
        assertTrue(StageQueue.stillOwed(scriptDir, TaskKind.OUTLINE, ""), "登记了还没做 → 欠着");

        // 工具跑完真写了文件 → 再记一次账，判 Done 并搬进归档
        Files.writeString(design, "# 设计稿\n## 第1章 · 开场\n", StandardCharsets.UTF_8);
        at(scriptDir, turn, TaskKind.OUTLINE);
        assertEquals(List.of(), readEntries("queue.json"), "做完的了结掉，不再欠");
        String archived = Files.readString(agentDir.resolve("queue-done.md"), StandardCharsets.UTF_8);
        assertTrue(archived.startsWith(StageQueue.QUEUE_DONE_HEADER), "归档表头逐字照抄原版");
        assertTrue(archived.contains("- [x] [outline@"), "改过 → [x]：" + archived);
    }

    @Test
    void existingArtifactMarksEqualNotDone() throws Exception {
        // 登记时就在、之后也没动 → [=]，不算做完也不算欠（磁盘是全新 tmp 目录，先写一份"上一轮的"账本）
        Instant turn = Instant.now().minusSeconds(120);
        at(scriptDir, Instant.now().minusSeconds(60), TaskKind.CHECK_ASSETS);
        Files.writeString(design, "# 设计稿\n", StandardCharsets.UTF_8);
        at(scriptDir, turn, TaskKind.OUTLINE);
        assertTrue(readEntries("queue.json").get(0).existedAtRegister(), "登记那一刻盘上已有");

        // 拿这一刻给 design.md 打时间戳：之后不论什么时候记账，都比"登记时刻"早 → 只能判 [=]
        Files.setLastModifiedTime(design, java.nio.file.attribute.FileTime.fromMillis(turn.toEpochMilli() - 1000L));

        at(scriptDir, turn, TaskKind.OUTLINE);
        assertEquals(List.of(), readEntries("queue.json"));
        String archived = Files.readString(agentDir.resolve("queue-done.md"), StandardCharsets.UTF_8);
        assertTrue(archived.contains("- [=] [outline@"), "在但没动 → [=]：" + archived);
    }

    @Test
    void leftoversFromPreviousTurnBecomeDropped() throws Exception {
        at(scriptDir, Instant.parse("2026-10-24T10:00:00Z"), TaskKind.CHECK_ASSETS);
        assertTrue(StageQueue.stillOwed(scriptDir, TaskKind.CHECK_ASSETS, ""));

        // 新的一轮排了别的事：上一轮没做完的那项改判 [~]，不再执行也不算做完
        at(scriptDir, Instant.parse("2026-10-24T10:05:00Z"), TaskKind.OUTLINE);
        String archived = Files.readString(agentDir.resolve("queue-done.md"), StandardCharsets.UTF_8);
        assertTrue(archived.contains("- [~] [check_assets@"), "上一轮没做完的改判 Dropped：" + archived);
        assertFalse(StageQueue.stillOwed(scriptDir, TaskKind.CHECK_ASSETS, ""), "旧项不再算欠");

        String queueMd = Files.readString(agentDir.resolve("queue.md"), StandardCharsets.UTF_8);
        assertTrue(queueMd.startsWith(StageQueue.QUEUE_HEADER), "视图表头逐字照抄原版");
        assertTrue(queueMd.contains("- [ ] [outline@"), "本轮没做完的画空格：" + queueMd);
    }

    @Test
    void progressLineQuotesOwedCountAndFirstItem() throws Exception {
        Instant turn = Instant.now().minusSeconds(2);
        at(scriptDir, turn, TaskKind.CHECK_ASSETS, TaskKind.OUTLINE);
        String progress = StageQueue.readQueueProgress(scriptDir);
        assertTrue(progress.startsWith("还欠 2 项"), progress);
        assertTrue(progress.contains("最早一项：素材盘点"), "最早一项按队列顺序：" + progress);
        assertTrue(progress.contains("别去改队列文件"), "原版那句提醒要带上：" + progress);

        Files.writeString(assets, "- 无\n", StandardCharsets.UTF_8);
        Files.writeString(design, "# 设计稿\n", StandardCharsets.UTF_8);
        at(scriptDir, turn, TaskKind.CHECK_ASSETS, TaskKind.OUTLINE);
        assertNull(StageQueue.readQueueProgress(scriptDir), "两项都做完 → 一项不欠，返回 null");
    }

    @Test
    void judgeableItemsOnlyEnterTheLedger() throws Exception {
        at(scriptDir, Instant.now().minusSeconds(1),
                TaskKind.CHAT, TaskKind.POLISH, TaskKind.WRITE_CHAPTER);
        assertEquals(List.of(), readEntries("queue.json"), "纯对话 / 只读体检 / 章号为空都不进账本");

        StageQueue.writeQueue(scriptDir,
                List.of(new QueueItem(TaskKind.WRITE_CHAPTER, "01")), Instant.now().minusSeconds(1), null);
        assertEquals(1, readEntries("queue.json").size(), "写了章号的章节类要进账本");
    }

    @Test
    void duplicateItemInSameTurnRegisteredOnce() throws Exception {
        at(scriptDir, Instant.now().minusSeconds(2),
                TaskKind.CHECK_ASSETS, TaskKind.CHECK_ASSETS);
        assertEquals(1, readEntries("queue.json").size(), "同 kind 同 target 去重");
    }

    @Test
    void emptyLedgerWritesNoFiles() throws Exception {
        at(scriptDir, Instant.now(), TaskKind.CHAT);
        assertFalse(Files.exists(agentDir.resolve("queue.json")), "无可核对项且原来没有账本 → 一个文件都不写");
        assertFalse(Files.exists(agentDir.resolve("queue.md")));
    }

    @Test
    void archiveCappedAndDeduplicated() throws Exception {
        for (int i = 0; i < 60; i++) {
            Instant turn = Instant.now().minusSeconds(120L - i * 2L);
            Files.writeString(assets, "第 " + i + " 轮\n", StandardCharsets.UTF_8);
            at(scriptDir, turn, TaskKind.CHECK_ASSETS);
            at(scriptDir, turn, TaskKind.CHECK_ASSETS);
        }
        List<StageQueue.Entry> archived = readEntries("queue-done.json");
        assertTrue(archived.size() <= 50, "归档只留最近 50 条，实际 " + archived.size());
        assertEquals(1, archived.stream()
                        .filter(e -> e.kind() == TaskKind.CHECK_ASSETS && e.mark() == StageQueue.Mark.DONE)
                        .count(),
                "同一件事、同一个结论只留一行");
    }

    // ───────────────────────── 读状态 ─────────────────────────

    private List<StageQueue.Entry> readEntries(String name) throws Exception {
        return StageQueueTestSupport.readEntries(agentDir.resolve(name));
    }

    /** 用 {@code kind} + 空目标排一轮（写法贴近主循环：一个登记时刻，整轮共用）。 */
    private static void at(Path dir, Instant turn, TaskKind... kinds) {
        List<QueueItem> items = java.util.Arrays.stream(kinds)
                .map(k -> new QueueItem(k, ""))
                .toList();
        StageQueue.writeQueue(dir, items, turn, null);
    }
}

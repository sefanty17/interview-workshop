package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.QueueItem;
import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 队列账本：{@code .agent/queue.json} 是真相，{@code queue.md} / {@code queue-done.md} 是它的视图。
 *
 * <p>1:1 迁移自原版 {@code stage/queue.rs}（271 行）。四处语义必须逐条对上：
 * <ul>
 *   <li>「这一项是不是本轮的」= 登记时刻相等（纳秒精度），不留时间容差；</li>
 *   <li>上一轮留下的 {@code Open} 出队时改判 {@code Dropped}（留痕，不当成做完）；</li>
 *   <li>登记后按盘上/库里的事实重新打勾：{@code Done} 才算做完，{@code Existing} 是不欠；</li>
 *   <li>了结的搬进归档，去重只看 {@code (kind, target, mark)}，只留最近 {@value #QUEUE_DONE_KEEP} 条。</li>
 * </ul>
 *
 * <p><b>本章节类的取数差异</b>（技术栈差异，不是功能裁剪）：原版章节就是 {@code Chapters/<id>.yaml}，
 * 本项目章节在 {@code interview_chapter} 表 —— 物证改走 {@link ItemEvidence}，它已经按 kind 分派好了。
 *
 * <p>账本是辅助记忆：写失败只记日志，不该把这一轮弄失败（对齐 {@code core.rs:511-521 save_queue}）。
 */
final class StageQueue {

    private static final Logger log = LoggerFactory.getLogger(StageQueue.class);

    /** 队列的机器状态（真相）相对剧本包根的路径（对齐 {@code stage::QUEUE_JSON_REL_PATH}）。 */
    static final String QUEUE_JSON_REL_PATH = ".agent/queue.json";

    /** 归档的机器状态：与上面同构，只是装着了结过的项。 */
    static final String QUEUE_DONE_JSON_REL_PATH = ".agent/queue-done.json";

    /** 队列视图（给人看的那份）。 */
    static final String QUEUE_REL_PATH = ".agent/queue.md";

    /** 归档视图。 */
    static final String QUEUE_DONE_REL_PATH = ".agent/queue-done.md";

    /** 归档保留条数上限：这是给人回看的流水，不该无限长。 */
    private static final int QUEUE_DONE_KEEP = 50;

    /** 视图里的登记时刻只到秒（{@code [write_chapter@1790664196]}）；状态里存的是完整时刻（纳秒）。 */
    private static final long NANOS_PER_SEC = 1_000_000_000L;

    /** 队列视图表头，逐字照抄 {@code prompts/stage_queue_header.md}。 */
    static final String QUEUE_HEADER =
            "# 任务队列（流程 Agent 维护）\n"
                    + "\n"
                    + "<!-- 只列**这一轮**还没做完的；有结论的搬进 queue-done.md。`- [ ]` 没做完 / `- [x]` 这一轮做完了 / "
                    + "`- [=]` 本来就在盘上、这一轮没动（不算欠）/ `- [~]` 没做完就被放下了（不再执行，也不算做完）。"
                    + "第二对方括号 [kind@时刻] 是任务类型与登记时刻。上一轮剩下的不会带到这一轮来。 -->\n";

    /** 归档视图表头，逐字照抄 {@code queue.rs:187-188}。 */
    static final String QUEUE_DONE_HEADER =
            "# 已归档（`[x]` 代码按磁盘事实核对过的、这一轮真做出来的，或上一轮已经勾过的；"
                    + "`[=]` 本来就在盘上、这一轮没动过（不算欠）；`[~]` 没做完就被放下的 —— 不再执行，也没当成做完）\n\n";

    /** 账本里的一项的状态。 */
    enum Mark {
        /** {@code - [ ]} 这一轮排的，还没做完。 */
        OPEN(' '),
        /** {@code - [x]} 这一轮真的做出来了（物证在登记之后被改写过）。 */
        DONE('x'),
        /** {@code - [=]} 东西本来就在盘上、这一轮没动过：不算做完也不算欠（只有产出型会落到这里）。 */
        EXISTING('='),
        /** {@code - [~]} 没做完就被放下了：既不算做完也不再执行，只留一行痕。 */
        DROPPED('~');

        private final char mark;

        Mark(char mark) {
            this.mark = mark;
        }

        char mark() {
            return mark;
        }

        /** JSON 里的值：与原版 serde 的变体名单一致（{@code "Open"} 等，不是小写）。 */
        static Mark parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (Mark m : values()) {
                if (m.name().equals(raw)) {
                    return m;
                }
            }
            return null;
        }
    }

    /**
     * 账本里的一项。
     *
     * @param mark              状态
     * @param kind              任务类型
     * @param target            目标（章号 / 大纲 / 素材表…），空串 = 未指明；去重与物证核对都只看它
     * @param registeredAt      登记时刻（Unix 纳秒），"这一项是不是本轮的"就是一次相等比较
     * @param existedAtRegister 登记那一刻产物在不在盘上（删除唯一的成功信号：登记时在、核账时不见了 = 删掉了）
     */
    record Entry(Mark mark, TaskKind kind, String target, long registeredAt, boolean existedAtRegister) {
    }

    private StageQueue() {
    }

    /**
     * 把本轮的账落盘：上一轮的项出队留痕、登记本轮的项、按磁盘事实打勾、做完的搬进归档，
     * 状态先写、视图后渲染（对齐 {@code queue.rs::write_queue}）。
     *
     * @param dir          剧本包目录 {@code <sandbox>/scripts/<key>}；{@code null} 时什么都不做
     * @param items        本轮路由排出来的队列
     * @param registeredAt 登记时刻（整轮一个，各项共用）
     * @param lander       章节物证入口（章节在库里，不在盘上）
     */
    static void writeQueue(Path dir, List<QueueItem> items, Instant registeredAt, ChapterLander lander) {
        if (dir == null) {
            return;
        }
        long now = epochNanos(registeredAt);
        ObjectMapper mapper = new ObjectMapper();

        // 一遍过（对齐原版第 1 次调用）：上一轮的项出队（Open→Dropped）、本轮的 DONE 搬进归档、
        // 本轮的新项按 (kind, target) 去重后登记。**此时不判物证** —— 原版只在每项工具跑完之后记账，
        // 这里提前判会把"本来就在"的项直接了结，后面的核心核对就没机会把它判成 Done。
        List<Entry> all = readState(dir, QUEUE_JSON_REL_PATH, mapper);
        List<Entry> kept = new ArrayList<>();
        List<Entry> closed = new ArrayList<>();
        boolean fromPreviousTurn = all.stream().anyMatch(e -> e.registeredAt() != now);
        for (Entry e : all) {
            if (e.registeredAt() != now) {
                closed.add(new Entry(e.mark() == Mark.OPEN ? Mark.DROPPED : e.mark(),
                        e.kind(), e.target(), e.registeredAt(), e.existedAtRegister()));
            } else if (e.mark() == Mark.DONE) {
                closed.add(e);
            } else {
                kept.add(e);
            }
        }

        for (QueueItem item : items) {
            if (item == null || item.kind() == null) {
                continue;
            }
            String target = item.target() == null ? "" : item.target().trim();
            if (!ItemEvidence.judgeable(item.kind(), target)) {
                continue;
            }
            boolean dup = kept.stream()
                    .anyMatch(e -> e.kind() == item.kind() && e.target().equals(target));
            if (dup) {
                continue;
            }
            kept.add(new Entry(Mark.OPEN, item.kind(), target, now,
                    ItemEvidence.artifactExists(dir, item.kind(), target, lander)));
        }

        List<Entry> stillOpen;
        if (fromPreviousTurn) {
            // 本轮第一次记账：只登记、不下结论（原版语义）
            stillOpen = kept;
        } else {
            // 本轮后续记账：按盘上/库里的事实打勾，有结论的搬进归档
            stillOpen = new ArrayList<>();
            for (Entry e : kept) {
                ItemEvidence.Evidence ev = ItemEvidence.entryEvidence(dir, e.kind(), e.target(),
                        e.registeredAt(), e.existedAtRegister(), lander);
                if (ev == ItemEvidence.Evidence.OPEN) {
                    stillOpen.add(e);
                } else {
                    closed.add(new Entry(ev == ItemEvidence.Evidence.DONE ? Mark.DONE : Mark.EXISTING,
                            e.kind(), e.target(), e.registeredAt(), e.existedAtRegister()));
                }
            }
        }

        if (closed.isEmpty() && stillOpen.isEmpty()
                && !Files.exists(dir.resolve(QUEUE_JSON_REL_PATH))
                && !Files.exists(dir.resolve(QUEUE_REL_PATH))) {
            return;
        }

        try {
            Files.createDirectories(dir.resolve(".agent"));
            writeState(dir, QUEUE_JSON_REL_PATH, stillOpen, mapper);
            writeAtomic(dir.resolve(QUEUE_REL_PATH), renderView(QUEUE_HEADER, stillOpen));
            archiveQueueDone(dir, closed, mapper);
        } catch (IOException e) {
            log.warn("[router] 队列落盘失败 {}：{}", dir, e.getMessage());
        }
    }

    /**
     * 这一项在账本上还欠着吗：登记了且仍是 {@code OPEN}。
     * 不在账本里的（不产出可核对产物、或已经了结）返回 {@code false}（对齐 {@code queue.rs::still_owed}）。
     */
    static boolean stillOwed(Path dir, TaskKind kind, String target) {
        if (dir == null) {
            return false;
        }
        String t = target == null ? "" : target.trim();
        return readState(dir, QUEUE_JSON_REL_PATH, new ObjectMapper()).stream()
                .anyMatch(e -> e.kind() == kind && e.target().equals(t) && e.mark() == Mark.OPEN);
    }

    /** 队列里还欠着的事，从机器状态算（{@code [=]} 不算欠、早已归档）；一项不欠返回 {@code null}。 */
    static String readQueueProgress(Path dir) {
        if (dir == null) {
            return null;
        }
        List<String> pending = new ArrayList<>();
        for (Entry e : readState(dir, QUEUE_JSON_REL_PATH, new ObjectMapper())) {
            if (e.mark() == Mark.OPEN) {
                pending.add(renderText(e));
            }
        }
        if (pending.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("还欠 ").append(pending.size()).append(" 项");
        out.append("（最早一项：").append(pending.get(0)).append("）");
        out.append("。这里列的就是还没做完的：用户说了不用做、或者已经改口做别的，"
                + "说一声就行，别去改队列文件（那是渲染出来的，改了不生效）");
        return out.toString();
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** 登记时刻与文件时刻都按 Unix 纳秒比（对齐 {@code queue.rs::epoch_nanos}）。 */
    static long epochNanos(Instant at) {
        if (at == null) {
            return 0L;
        }
        return at.getEpochSecond() * NANOS_PER_SEC + at.getNano();
    }

    /** 一项的正文（{@code 类型标签：目标}），队列视图与注入的进度行共用。 */
    private static String renderText(Entry entry) {
        return SkillAgentService.renderTextOf(entry.kind(), entry.target());
    }

    /** 渲染一份视图：行格式 {@code - [ ] [kind@epoch] 正文}。 */
    private static String renderView(String header, List<Entry> entries) {
        StringBuilder out = new StringBuilder(header);
        for (Entry entry : entries) {
            long at = entry.registeredAt() / NANOS_PER_SEC;
            out.append("- [").append(entry.mark().mark()).append("] [")
                    .append(entry.kind().key()).append('@').append(at).append("] ")
                    .append(renderText(entry)).append('\n');
        }
        return out.toString();
    }

    /** 读机器状态。文件不在 / 读不动 / 解析失败 → 空账：宁可从这一轮重新记，也不拿半份状态去判。 */
    private static List<Entry> readState(Path dir, String rel, ObjectMapper mapper) {
        List<Entry> out = new ArrayList<>();
        Path file = dir.resolve(rel);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return out;
        }
        if (root == null || !root.isArray()) {
            return out;
        }
        for (JsonNode node : root) {
            // 与原版一致：解析失败的整份丢弃（serde 做不到"跳过坏项"），不是逐项跳过
            Mark mark = Mark.parse(text(node, "mark"));
            TaskKind kind = TaskKind.parse(text(node, "kind"));
            if (mark == null || kind == null) {
                return new ArrayList<>();
            }
            out.add(new Entry(mark, kind, text(node, "target"),
                    node.path("registeredAt").asLong(0L), node.path("existedAtRegister").asBoolean(false)));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** 写机器状态（JSON），同样先写临时文件再改名。 */
    private static void writeState(Path dir, String rel, List<Entry> entries, ObjectMapper mapper)
            throws IOException {
        ArrayNode array = mapper.createArrayNode();
        for (Entry e : entries) {
            ObjectNode node = array.addObject();
            node.put("mark", e.mark().name());
            node.put("kind", e.kind().key());
            node.put("target", e.target());
            node.put("registeredAt", e.registeredAt());
            node.put("existedAtRegister", e.existedAtRegister());
        }
        writeAtomic(dir.resolve(rel), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(array));
    }

    /** 先写同目录下的 {@code .tmp} 再改名：撞上中断会丢账本；{@code .tmp} 结尾不会被产物枚举认领。 */
    private static void writeAtomic(Path path, String text) throws IOException {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        Path tmp = path.resolveSibling(name + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不影响结论
            }
            throw e;
        }
    }

    /** 归档去重只看 {@code (kind, target, mark)}：同一件事、同一个结论只留一行。 */
    private static boolean sameConclusion(Entry a, Entry b) {
        return a.kind() == b.kind() && a.target().equals(b.target()) && a.mark() == b.mark();
    }

    /** 把了结的项搬进归档状态并重渲染归档视图，只留最近 {@value #QUEUE_DONE_KEEP} 条。 */
    private static void archiveQueueDone(Path dir, List<Entry> closed, ObjectMapper mapper) throws IOException {
        if (closed.isEmpty()) {
            return;
        }
        List<Entry> entries = readState(dir, QUEUE_DONE_JSON_REL_PATH, mapper);
        for (Entry e : closed) {
            boolean dup = entries.stream().anyMatch(old -> sameConclusion(old, e));
            if (!dup) {
                entries.add(e);
            }
        }
        if (entries.size() > QUEUE_DONE_KEEP) {
            entries = new ArrayList<>(entries.subList(entries.size() - QUEUE_DONE_KEEP, entries.size()));
        }
        writeState(dir, QUEUE_DONE_JSON_REL_PATH, entries, mapper);
        writeAtomic(dir.resolve(QUEUE_DONE_REL_PATH), renderView(QUEUE_DONE_HEADER, entries));
    }
}

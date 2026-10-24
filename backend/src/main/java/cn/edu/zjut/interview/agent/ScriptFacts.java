package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 承接任务时需要的磁盘事实，每轮重算、不落库。
 *
 * <p>1:1 迁移自 {@code role.rs::ScriptFacts}。刻意独立于 {@code Stage}：投影会丢信息，
 * 而"能不能做"看的正是事实本身。
 *
 * <p><b>本项目与原版的取数差异</b>：原版章节就是 {@code Chapters/<id>.yaml} 文件，
 * {@code written} 直接列目录；本项目章节存在 {@code interview_chapter} 表，
 * 所以 {@code written} 走库（见 {@link ChapterLander#writtenChapterIds}），
 * 设计稿与细节稿仍是沙箱里的 {@code .agent/*.md}。
 */
public record ScriptFacts(
        /** 设计稿文件存在且非空。 */
        boolean hasDesign,
        /** 设计稿能解析出章节列表；与 {@code hasDesign} 分开：文件在但格式不对只提醒补格式，不是做不了。 */
        boolean hasPlan,
        boolean hasWritten,
        boolean hasNext,
        /** 用户点名的目标存在吗；{@code null} = 这一轮没点名。 */
        Boolean targetExists,
        /** 点名的目标里有的在、有的不在：照做能做的部分并说明缺哪几章，不能整轮拒绝。 */
        boolean targetPartial,
        /** 点名的这一章正好是设计稿里下一个待写的章：用来认出"跳着写"。 */
        boolean targetIsNext,
        /** 点名的这一章在 {@code .agent/chapter-details.md} 里有没有剧情小节；落盘只做转换，所以这是它唯一的前置。 */
        boolean hasDraft) {

    /** 设计稿相对路径（对齐 {@code stage::DESIGN_REL_PATH}）。 */
    static final String DESIGN_REL_PATH = ".agent/design.md";

    /** 章节细节稿相对路径（对齐 {@code stage::CHAPTER_DETAILS_REL_PATH}）。 */
    static final String CHAPTER_DETAILS_REL_PATH = ".agent/chapter-details.md";

    /** 空事实：什么都还没有。 */
    public static ScriptFacts empty() {
        return new ScriptFacts(false, false, false, false, null, false, false, false);
    }

    /**
     * 这一轮的任务能不能直接做，不能的话往哪走；每个格子都必须有确定答案，没有"其他"兜底分支。
     *
     * <p>判据全部来自 {@link ScriptFacts}、可被代码重算；前置只补一级不递归，
     * 补完磁盘事实变了，下一轮自然落到能做的那一档。
     *
     * <p>用一个 record 装"往哪走 + 为什么"，因为枚举常量不可变、挂不住每轮不同的文案。
     */
    public record Handoff(Kind kind, TaskKind first, String note) {

        /** 判定结果的三档 + 一档直通。 */
        public enum Kind {
            /** 直接做。 */
            PROCEED,
            /** 照做，但交接单里带一句提醒（不换任务、不动工具）。 */
            PROCEED_NOTE,
            /** 先做另一项；同一轮接着做用户原本要的那件。 */
            PREREQUISITE,
            /** 当前状态做不了，也不该硬做：说清楚，不动文件。 */
            EXPLAIN
        }

        static final Handoff PROCEED = new Handoff(Kind.PROCEED, null, null);

        static Handoff proceedNote(String note) {
            return new Handoff(Kind.PROCEED_NOTE, null, note);
        }

        static Handoff prerequisite(TaskKind first, String note) {
            return new Handoff(Kind.PREREQUISITE, first, note);
        }

        static Handoff explain(String note) {
            return new Handoff(Kind.EXPLAIN, null, note);
        }

        /** 这一档是不是"可以直接动手"。 */
        public boolean canAct() {
            return kind == Kind.PROCEED || kind == Kind.PROCEED_NOTE;
        }
    }

    /**
     * 承接判断：判据全部来自 {@link ScriptFacts}，可被代码重算。
     *
     * <p>文案 1:1 迁移自 {@code role.rs::reconcile}，含 {@code role_reconcile_write_chapter.md}。
     */
    public static Handoff reconcile(TaskKind kind, ScriptFacts facts) {
        return switch (kind) {
            case CHAT, COLLECT, SUMMARIZE, OUTLINE, DRAFT_CHAPTER, CHECK_ASSETS ->
                    Handoff.PROCEED;

            case REVISE_OUTLINE -> {
                if (facts.hasDesign()) {
                    yield Handoff.PROCEED;
                }
                yield Handoff.prerequisite(TaskKind.OUTLINE, "还没有设计稿，先把设计稿写出来才谈得上改它");
            }

            case WRITE_CHAPTER -> {
                if (facts.targetExists() != null && !facts.hasDraft()) {
                    yield Handoff.prerequisite(TaskKind.DRAFT_CHAPTER,
                            "细节稿里还没有这一章的正文，先让编剧把它写出来");
                }
                if (!facts.hasPlan()) {
                    if (facts.hasDesign()) {
                        yield Handoff.proceedNote(
                                "设计稿文件在，但读不出章节列表 —— 章节标题要 `## ` 开头、"
                                        + "紧随其后的第一个非空行写 `id: <章节id>`；这一轮按用户说的写，"
                                        + "读不出的格式问题记进回执（改设计稿归「改大纲」那一项）");
                    }
                    yield Handoff.proceedNote(
                            "还没有设计稿：这一轮按用户说的转；**大纲不归你写**（设计稿由「编写大纲」"
                                    + "那一项产出），回执里说一句这一章没有大纲依据、要另排一项");
                }
                if (!facts.hasNext()) {
                    yield Handoff.proceedNote(
                            "设计稿里列的章节都写完了；这一章是新加的，**别顺手往设计稿里补一节** ——"
                                    + "回执里说一句得再排一项「编写大纲 / 改大纲」把这一章补进设计稿");
                }
                if (facts.targetIsNext() || !Boolean.FALSE.equals(facts.targetExists())) {
                    yield Handoff.PROCEED;
                }
                yield Handoff.proceedNote(WRITE_CHAPTER_SKIP_NOTE);
            }

            case REVISE_CHAPTER -> {
                if (!facts.hasWritten()) {
                    yield Handoff.explain("还没有任何章节转成 YAML，没得改");
                }
                if (Boolean.FALSE.equals(facts.targetExists())) {
                    yield Handoff.explain("你点名的那一章还没有转成 YAML，改不了它");
                }
                if (facts.targetPartial()) {
                    yield Handoff.proceedNote(
                            "用户点名的章节里有还没落盘的：这一轮先改已经落盘的那些，"
                                    + "并在回执里点明哪几章还没有、所以这轮没动");
                }
                yield Handoff.PROCEED;
            }

            case POLISH -> {
                if (!facts.hasWritten()) {
                    yield Handoff.explain("还没有章节可以校验");
                }
                if (facts.hasNext()) {
                    yield Handoff.proceedNote(
                            "剧本还没写完：整剧校验会报一批「尚未写完」的假错（断链、不可达），"
                                    + "不要照着那些去提前补写后续章节；只处理与已落盘章节有关的问题");
                }
                yield Handoff.PROCEED;
            }
        };
    }

    /** {@code prompts/role_reconcile_write_chapter.md} 原文。 */
    private static final String WRITE_CHAPTER_SKIP_NOTE =
            "用户点名的那一章不是设计稿里下一个待写的：前面还有没落盘的章，直接写它会接不上"
                    + "（上一章的 chapter_end 指向缺的那一章）。他没明说「知道会接不上，就要跳着写」时，"
                    + "把这件事记进回执（要他拍板的话留给本轮最后那一项统一问）；"
                    + "他确认了就照写，并在回执里说明它暂时接不上";

    /**
     * 渲染交接单（对齐 {@code role.rs::render_handoff}）。
     *
     * <p>{@code PREREQUISITE} 的措辞必须说成"同一轮"：说「再继续」会被读成"下一轮还有机会"，
     * 它做完前置就收尾了。
     */
    public static String renderHandoff(Handoff handoff) {
        if (handoff == null) {
            return "";
        }
        return switch (handoff.kind()) {
            case PROCEED -> "";
            case PROCEED_NOTE -> "本轮照做，但注意：" + handoff.note() + "。";
            case PREREQUISITE -> "本轮实际要先做「" + handoff.first().label() + "」："
                    + handoff.note() + "。**做完这一项，同一轮会接着做用户原本要的那件** ——"
                    + " 不用等下一轮、也不用问用户，两件都做完这一轮才算完。";
            case EXPLAIN -> "本轮不做这件事：" + handoff.note() + "。把原因告诉用户，不要动任何文件。";
        };
    }

    // ───────────────────────── 取数 ─────────────────────────

    /**
     * 从沙箱与剧本库里取事实。
     *
     * @param scriptDir 剧本包目录（沙箱下的 {@code <sandbox>/scripts/<key>}）；不知道就传 {@code null}
     * @param target    用户点名的章节（路由给的原样字符串，可能为空、可能是 {@code 02,03} 或 {@code 2-4}）
     * @param written   库里已有的章节 id（{@link ChapterLander#writtenChapterIds}）
     */
    public static ScriptFacts collect(Path scriptDir, String target, List<String> written) {
        List<String> wanted = chapterIdsIn(target);
        List<String> writtenIds = written == null ? List.of() : written;

        List<String> plan = List.of();
        boolean hasDesign = false;
        if (scriptDir != null) {
            String design = readOrEmpty(scriptDir.resolve(DESIGN_REL_PATH));
            hasDesign = !design.isBlank();
            plan = parsePlan(design);
        }

        List<Boolean> present = new ArrayList<>(wanted.size());
        for (String id : wanted) {
            present.add(containsChapter(writtenIds, id));
        }
        Boolean targetExists = wanted.isEmpty() ? null
                : present.stream().anyMatch(Boolean::booleanValue);
        boolean targetPartial = present.stream().anyMatch(Boolean::booleanValue)
                && present.stream().anyMatch(p -> !p);

        String next = plan.stream().filter(id -> !containsChapter(writtenIds, id)).findFirst().orElse(null);
        boolean targetIsNext = next != null && wanted.stream().anyMatch(w -> sameChapter(w, next));

        boolean hasDraft = false;
        if (scriptDir != null && !wanted.isEmpty()) {
            String details = readOrEmpty(scriptDir.resolve(CHAPTER_DETAILS_REL_PATH));
            List<String> keys = wanted.stream().map(ScriptFacts::chapterKey).toList();
            hasDraft = chapterTitled(details, keys);
        }

        return new ScriptFacts(
                hasDesign,
                !plan.isEmpty(),
                !writtenIds.isEmpty(),
                next != null,
                targetExists,
                targetPartial,
                targetIsNext,
                hasDraft);
    }

    /**
     * 设计稿里列出的章节 code（按设计稿顺序）；文件不在或读不出章节列表就是空表。
     *
     * <p>路由要看「设计稿已列出章节」这一行（{@code router.rs:161-165}），
     * 事实本身只记 {@code hasPlan} 一个布尔，所以这里再解析一遍（每轮一次，可忽略）。
     */
    public static List<String> planChapters(Path scriptDir) {
        if (scriptDir == null) {
            return List.of();
        }
        return parsePlan(readOrEmpty(scriptDir.resolve(DESIGN_REL_PATH)));
    }

    /** 设计稿正文 → 章节 code 列表（与 {@link #planChapters} 同一套解析，只是文本已读好）。 */
    public static List<String> planChaptersOf(String design) {
        return parsePlan(design);
    }

    /** 章节号归一后相等（{@code 03} 与 {@code 3} 算同一章）。 */
    private static boolean containsChapter(List<String> ids, String wanted) {
        return ids.stream().anyMatch(id -> sameChapter(id, wanted));
    }

    private static boolean sameChapter(String a, String b) {
        String ka = chapterKey(a);
        String kb = chapterKey(b);
        if (ka.isEmpty() || kb.isEmpty()) {
            return a == null ? b == null : a.trim().equals(b == null ? null : b.trim());
        }
        return ka.equals(kb);
    }

    /** 章节号归一：取第一个数字串去掉前导零；不含数字的返回空串，不参与比较。 */
    static String chapterKey(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder run = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                run.append(ch);
            } else if (run.length() > 0) {
                break;
            }
        }
        String digits = run.toString();
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    /** 一个区间最多展开这么多章，防止 {@code 01-9999} 把内存撑爆（对齐原版 {@code MAX_SPAN}）。 */
    private static final int MAX_SPAN = 200;

    /** 用户点名的章节 id 列表（{@code 第 3 章} 归一成 {@code 03}）；区间与列表都要拆开。 */
    static List<String> chapterIdsIn(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,，、;；\\s]")) {
            String p = normalizeChapterToken(part);
            if (p.isEmpty()) {
                continue;
            }
            int dash = firstDash(p);
            if (dash < 0) {
                out.add(p);
                continue;
            }
            String a = p.substring(0, dash).trim();
            String b = p.substring(dash + 1).replaceFirst("^[-~–—]+", "").trim();
            if (isDigits(a) && isDigits(b)) {
                int start = Integer.parseInt(a);
                int end = Integer.parseInt(b);
                if (start <= end && end - start <= MAX_SPAN) {
                    int width = Math.max(a.length(), b.length());
                    for (int n = start; n <= end; n++) {
                        out.add(String.format("%0" + width + "d", n));
                    }
                    continue;
                }
            }
            out.add(p);
        }
        return new ArrayList<>(out);
    }

    private static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static int firstDash(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '~' || c == '–' || c == '—') {
                return i;
            }
        }
        return -1;
    }

    /** {@code 第 3 章} / {@code 03节} / {@code "03"} → {@code 03}（去空白与引号，剥掉「第/章/节」）。 */
    private static String normalizeChapterToken(String raw) {
        String t = raw == null ? "" : raw.trim();
        t = stripChars(t, "\"'`《》「」");
        if (t.startsWith("第")) {
            t = t.substring(1);
        }
        if (t.endsWith("章") || t.endsWith("节")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.trim();
    }

    private static String stripChars(String s, String chars) {
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

    /** {@code ## <标题>} 块里第二行起第一个非空行写 {@code id: xx} 的，就是一章。 */
    private static List<String> parsePlan(String markdown) {
        List<String> plan = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return plan;
        }
        String[] lines = markdown.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].stripLeading().startsWith("##")) {
                continue;
            }
            // 从下一个 ## 之前找 id:
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].stripLeading().startsWith("##")) {
                    break;
                }
                String line = lines[j].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("id:")) {
                    String id = stripChars(line.substring(3).trim(), "\"'").trim();
                    if (!id.isEmpty() && !plan.contains(id)) {
                        plan.add(id);
                    }
                }
                break; // 只看紧随其后的第一个非空行
            }
        }
        return plan;
    }

    /** 稿子里有没有点名这一章的小节（{@code ## 第4章 · …} / {@code ## 第4章}）：只看标题行。 */
    private static boolean chapterTitled(String text, List<String> keys) {
        if (text == null || text.isBlank()) {
            return false;
        }
        List<String> wanted = keys.stream().filter(k -> !k.isEmpty()).toList();
        if (wanted.isEmpty()) {
            return false;
        }
        for (String line : text.split("\r?\n", -1)) {
            String t = line.stripLeading();
            if (!t.startsWith("#")) {
                continue;
            }
            String key = chapterKey(t);
            if (wanted.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String readOrEmpty(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}

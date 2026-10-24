package cn.edu.zjut.interview.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 渲染交接材料用的快照（原版 {@code stage::StageSnapshot} 的等价物）。
 *
 * <p>原版的快照里有 {@code stage}（Setup / Modify / Forge …）、{@code design}、{@code plan}、
 * {@code written}、{@code design_mtime} 等字段，由 {@code stage::derive} 按盘上文件与队列算出来。
 * 本项目只迁「交接材料」用到的那几个字段，阶段判定见 {@link StagePrompt#buildRunMaterials} 的 javadoc。
 *
 * <p><b>取数差异（技术栈差异）</b>：原版章节就是 {@code Chapters/<id>.yaml}，
 * {@code written} 直接列目录；本项目章节在 {@code interview_chapter} 表（实际落点见
 * {@code ChapterLander.writtenChapterIds}），设计稿与细节稿仍是剧本包里的 {@code .agent/*.md}。
 *
 * @param scriptKey    会话绑定的剧本 key；{@code null} = 没打开剧本
 * @param scriptDir    剧本包目录 {@code <sandbox>/scripts/<key>}；{@code null} 时整段交接材料为空
 * @param design       设计稿全文；文件不在 → {@code null}（空文件算「在」）
 * @param plan         设计稿里读出的章节 id（按设计稿顺序）
 * @param written      已落盘的章节 id（排好序）
 * @param constraints  {@code .agent/constraints.md} 全文；读不到 → 空串
 */
public record StageFacts(
        String scriptKey,
        Path scriptDir,
        String design,
        List<String> plan,
        List<String> written,
        String constraints) {

    public StageFacts {
        plan = plan == null ? List.of() : List.copyOf(plan);
        written = written == null ? List.of() : List.copyOf(written);
        constraints = constraints == null ? "" : constraints;
    }

    /** 空快照（没绑定剧本）：交接材料整段不出现。 */
    public static StageFacts empty() {
        return new StageFacts(null, null, null, List.of(), List.of(), "");
    }

    /**
     * 从剧本包与库里取一份快照。
     *
     * @param scriptKey 剧本 key；{@code null} 时返回 {@link #empty()}
     * @param scriptDir 剧本包目录（{@code null} 时同样返回空快照）
     * @param written   已落盘章节 id（调用方从库里/盘上取好）
     */
    public static StageFacts collect(String scriptKey, Path scriptDir, List<String> written) {
        if (scriptDir == null) {
            return empty();
        }
        Path designFile = scriptDir.resolve(ScriptFacts.DESIGN_REL_PATH);
        String design = Files.isRegularFile(designFile) ? readOrEmpty(designFile) : null;
        // 设计稿只读一次，章节列表从同一份文本解析（不再走 planChapters 二次读盘）
        List<String> plan = design == null ? List.of() : ScriptFacts.planChaptersOf(design);
        List<String> sorted = written == null ? List.of() : new ArrayList<>(written);
        sorted.sort(String::compareTo);
        return new StageFacts(scriptKey, scriptDir, design, plan, sorted,
                readOrEmpty(scriptDir.resolve(ScriptModes.CONSTRAINTS_REL_PATH)));
    }

    /** 第一个还没落盘的设计稿章节（{@code snap.next_chapter()}）。 */
    public String nextChapter() {
        for (String id : plan) {
            if (!written.contains(id)) {
                return id;
            }
        }
        return null;
    }

    /** 已落盘章节里最后写好的那一章（{@code snap.last_written()}，{@code written} 已排序）。 */
    public String lastWritten() {
        return written.isEmpty() ? null : written.get(written.size() - 1);
    }

    private static String readOrEmpty(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}

package cn.edu.zjut.interview.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Markdown 切分：把一篇 md 变成若干「一个知识点一段」的片段。
 *
 * <p><b>与 {@code scripts/import_knowledge.py} 的切分规则必须逐条一致</b>——
 * 脚本负责批量导入、这里负责前端上传，两条入口切出来的粒度不同的话，
 * 同一篇文档会出现「谁后导入谁说了算」的诡异现象。规则如下（两边同时改）：
 * <ol>
 *   <li>按 {@code #}（H1）与 {@code ##}（H2）切；{@code ###} 及更细的层级不切，归入所属小节。</li>
 *   <li>面包屑形如 {@code H1 > H2}；H1 之后、第一个 H2 之前的引言段用 H1 当标题。</li>
 *   <li>超过 {@link #MAX_CHUNK_CHARS} 的小节按空行二次切；尾段短于 {@link #MIN_CHUNK_CHARS}
 *       时并入上一段，避免出现「一句话一个片段」。</li>
 * </ol>
 */
public final class MarkdownChunker {

    /** 单片段目标上限。与 import_knowledge.py 的 MAX_CHUNK_CHARS 同值。 */
    static final int MAX_CHUNK_CHARS = 900;
    /** 尾段短于这个长度就并入上一段。与 import_knowledge.py 的 MIN_CHUNK_CHARS 同值。 */
    static final int MIN_CHUNK_CHARS = 40;

    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");

    private MarkdownChunker() {
    }

    /** 切片结果：标题（面包屑）+ 正文。 */
    public record Chunk(String heading, String text) {
    }

    /** 全文 → 片段列表。空文档返回空列表（不是「一个空片段」）。 */
    public static List<Chunk> chunk(String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return chunks;
        }
        String h1 = null;
        String h2 = null;
        StringBuilder buffer = new StringBuilder();
        List<String[]> sections = new ArrayList<>();

        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith("# ")) {
                flush(sections, buffer, h1, h2);
                h1 = stripped.substring(2).strip();
                h2 = null;
            } else if (stripped.startsWith("## ")) {
                flush(sections, buffer, h1, h2);
                h2 = stripped.substring(3).strip();
            } else {
                // ### 及以下当正文，不切
                buffer.append(line).append('\n');
            }
        }
        flush(sections, buffer, h1, h2);

        for (String[] section : sections) {
            chunks.addAll(splitLong(section[0], section[1]));
        }
        return chunks;
    }

    private static void flush(List<String[]> sections, StringBuilder buffer, String h1, String h2) {
        String body = buffer.toString().strip();
        buffer.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        String heading;
        if (h2 != null) {
            heading = h1 == null ? h2 : h1 + " > " + h2;
        } else {
            heading = h1 == null ? "" : h1;
        }
        sections.add(new String[]{heading, body});
    }

    /** 超长小节按空行二次切；不足 MIN 的尾段并入上一段。 */
    private static List<Chunk> splitLong(String heading, String body) {
        List<Chunk> out = new ArrayList<>();
        if (body.length() <= MAX_CHUNK_CHARS) {
            out.add(new Chunk(heading, body));
            return out;
        }
        List<String> paragraphs = new ArrayList<>();
        for (String part : BLANK_LINE.split(body)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (current.length() > 0 && current.length() + paragraph.length() + 2 > MAX_CHUNK_CHARS) {
                out.add(new Chunk(heading, current.toString()));
                current.setLength(0);
                current.append(paragraph);
            } else {
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(paragraph);
            }
        }
        if (current.length() > 0) {
            if (!out.isEmpty() && current.length() < MIN_CHUNK_CHARS) {
                Chunk last = out.remove(out.size() - 1);
                out.add(new Chunk(heading, last.text() + "\n\n" + current));
            } else {
                out.add(new Chunk(heading, current.toString()));
            }
        }
        return out;
    }
}

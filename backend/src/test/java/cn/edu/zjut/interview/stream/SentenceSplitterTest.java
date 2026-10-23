package cn.edu.zjut.interview.stream;

import cn.edu.zjut.interview.stream.SentenceSplitter.Sentence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 分句测试，对应 {@code producer.rs:89}。
 */
class SentenceSplitterTest {

    /** 一次性喂完 + finish。 */
    private static List<Sentence> splitAll(String text) {
        return splitAll(List.of(text));
    }

    private static List<Sentence> splitAll(List<String> chunks) {
        SentenceSplitter splitter = new SentenceSplitter();
        List<Sentence> out = new ArrayList<>();
        for (String chunk : chunks) {
            out.addAll(splitter.feed(chunk));
        }
        out.addAll(splitter.finish());
        return out;
    }

    /** 按字符切片，模拟网络 chunk 边界。 */
    private static List<String> chunksOf(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    @Test
    @DisplayName("【平静】你好啊【冷淡】今天不谈这个 → 2 句")
    void splitsOnTagBoundary() {
        List<Sentence> out = splitAll("【平静】你好啊【冷淡】今天不谈这个");

        assertEquals(2, out.size());
        assertEquals("【平静】你好啊", out.get(0).text());
        assertEquals(0, out.get(0).index());
        assertEquals(false, out.get(0).isFinal());
        assertEquals("【冷淡】今天不谈这个", out.get(1).text());
        assertEquals(1, out.get(1).index());
        assertTrue(out.get(1).isFinal());
    }

    @Test
    @DisplayName("每次 3 字符分片喂 → 与一次性喂完全相同")
    void chunkSizeDoesNotAffectResult() {
        String text = "【平静】你好啊，先坐（点头）\n【冷淡】今天不谈这个\n\n【疑惑】为什么？";

        List<Sentence> whole = splitAll(text);
        List<Sentence> chunked = splitAll(chunksOf(text, 3));

        assertEquals(3, whole.size());
        assertEquals(whole, chunked);
    }

    @Test
    @DisplayName("越界：三空行 + user 之后的内容丢弃，只出 1 句")
    void dropsContentAfterTurnEnd() {
        SentenceSplitter splitter = new SentenceSplitter();
        List<Sentence> out = new ArrayList<>(splitter.feed("【平静】好\n\n\nuser\n我不能说"));
        out.addAll(splitter.finish());

        assertEquals(1, out.size());
        assertEquals("【平静】好", out.get(0).text());
        assertTrue(out.get(0).isFinal());
        assertTrue(splitter.isTurnEnded());
    }

    @Test
    @DisplayName("越界：整行 user 标签单独也能截断（不带三空行）")
    void dropsContentAfterUserLabel() {
        SentenceSplitter splitter = new SentenceSplitter();
        List<Sentence> out = new ArrayList<>(splitter.feed("【平静】好\nuser\n我不能说"));
        out.addAll(splitter.finish());

        assertEquals(1, out.size());
        assertEquals("【平静】好\n", out.get(0).text());
        assertTrue(splitter.isTurnEnded());
    }
}

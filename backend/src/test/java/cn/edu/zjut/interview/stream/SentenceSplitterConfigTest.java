package cn.edu.zjut.interview.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code chat.merge_threshold} / {@code chat.reply_count} 对分句器的影响（规格 B4-BE §三）。
 *
 * <p>两个参数都是 0 时必须与旧行为逐字一致——这是「不影响已验证链路」的底线。
 */
class SentenceSplitterConfigTest {

    /** 三段正文：第一段很短（5 字），后两段较长。 */
    private static final String STREAM = "【开心】好的，我们开始吧。先说说的项目经历吧，你做过什么有意思的东西吗？"
            + "【平静】听起来不错，那你是怎么保证一致性的？"
            + "【开心】明白了，今天就到这里。";

    @Test
    void zeroThresholdKeepsOneSentencePerTag() {
        List<String> lines = split(new SentenceSplitter(0, 0), STREAM);
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("好的，我们开始吧"));
    }

    @Test
    void shortSentenceIsMergedIntoNextWhenThresholdEnabled() {
        List<String> merged = split(new SentenceSplitter(55, 0), STREAM);
        List<String> plain = split(new SentenceSplitter(0, 0), STREAM);

        assertTrue(merged.size() < plain.size(), "开启合并后条数必须变少");
        // 第一段（含标签也只有十来个字）被并进第二句，不再单独出现
        assertTrue(merged.get(0).contains("好的，我们开始吧") && merged.get(0).contains("项目经历"),
                merged.get(0));
    }

    @Test
    void replyCountCapsPublishedSentences() {
        List<String> capped = split(new SentenceSplitter(0, 1), STREAM);
        assertEquals(1, capped.size(), "reply_count=1 时只允许 1 句");
        assertTrue(capped.get(0).contains("好的，我们开始吧"));
    }

    @Test
    void capZeroMeansUnlimited() {
        assertEquals(3, split(new SentenceSplitter(0, 0), STREAM).size());
    }

    @Test
    void mergeAndCapWorkTogether() {
        List<String> lines = split(new SentenceSplitter(55, 2), STREAM);
        assertTrue(lines.size() <= 2, "合并 + 上限同时生效，最多 2 句");
        assertTrue(lines.size() >= 1);
    }

    /** 按 7 个字符一段喂进去（模拟网络 chunk 边界），收集全部投递出去的句子。 */
    private static List<String> split(SentenceSplitter splitter, String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 7) {
            String chunk = text.substring(i, Math.min(text.length(), i + 7));
            for (SentenceSplitter.Sentence sentence : splitter.feed(chunk)) {
                out.add(sentence.text());
            }
        }
        for (SentenceSplitter.Sentence sentence : splitter.finish()) {
            out.add(sentence.text());
        }
        return out;
    }
}

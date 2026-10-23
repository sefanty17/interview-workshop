package cn.edu.zjut.interview.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 情绪词典测试，对应规格 §4.3。
 */
class EmotionDictionaryTest {

    private final EmotionDictionary dictionary = EmotionDictionary.getInstance();

    @Test
    @DisplayName("词典加载完整：20 个枚举 + 82 条别名")
    void loadsSeedDictionary() {
        assertEquals(20, dictionary.emotions().size());
        assertEquals(82, dictionary.aliases().size());
    }

    @Test
    @DisplayName("别名折叠 / 未命中回落 / 空值兜底")
    void predictThreeBranches() {
        assertEquals("平静", dictionary.predict("冷淡"));
        // 语义变更（计划 §3.2）：不在枚举也不在别名 → 回落「正常」，不再原样返回
        assertEquals("正常", dictionary.predict("未知情绪"));
        assertEquals("正常", dictionary.predict(""));
        assertEquals("正常", dictionary.predict(null));
    }

    @Test
    @DisplayName("未识别标签回落「正常」：真实 DeepSeek 输出的「提醒」不再透传")
    void unknownTagFallsBackToNormal() {
        assertEquals("正常", dictionary.predict("提醒"));
    }

    @Test
    @DisplayName("别名折叠仍然生效：冷淡 → 平静")
    void aliasStillCollapses() {
        assertEquals("平静", dictionary.predict("冷淡"));
    }

    @Test
    @DisplayName("标准枚举原样返回：平静 → 平静")
    void standardEmotionPassesThrough() {
        assertEquals("平静", dictionary.predict("平静"));
    }

    @Test
    @DisplayName("空标签兜底：null / 空串 → 正常")
    void blankTagFallsBackToNormal() {
        assertEquals("正常", dictionary.predict(null));
        assertEquals("正常", dictionary.predict(""));
    }

    @Test
    @DisplayName("兜底情绪就是枚举里的正常")
    void fallbackIsNormal() {
        assertTrue(dictionary.emotions().contains("正常"));
    }
}

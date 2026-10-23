package cn.edu.zjut.interview.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * F2 符号解析测试，对应 {@code processor.rs:120 / 264}。
 */
class SymbolParserTest {

    private final SymbolParser parser = new SymbolParser();

    @Test
    @DisplayName("【冷淡】走吧（转身） → predicted 折叠为平静，motionText=转身")
    void parsesEmotionAndMotion() {
        List<EmotionSegment> segments = parser.parse("【冷淡】走吧（转身）");

        assertEquals(1, segments.size());
        EmotionSegment segment = segments.get(0);
        assertEquals(1, segment.index());
        assertEquals("冷淡", segment.originalTag());
        assertEquals("走吧", segment.followingText());
        assertEquals("转身", segment.motionText());
        assertEquals("平静", segment.predicted());
    }

    @Test
    @DisplayName("【未知情绪】x → originalTag 保留原文，predicted 回落「正常」")
    void unknownEmotionFallsBackToNormal() {
        List<EmotionSegment> segments = parser.parse("【未知情绪】x");

        assertEquals(1, segments.size());
        assertEquals("未知情绪", segments.get(0).originalTag());
        assertEquals("正常", segments.get(0).predicted());
        assertEquals("x", segments.get(0).followingText());
    }

    @Test
    @DisplayName("<こんにちは>【平静】hi → japaneseText=こんにちは")
    void parsesJapaneseBeforeTag() {
        List<EmotionSegment> segments = parser.parse("<こんにちは>【平静】hi");

        assertEquals(1, segments.size());
        assertEquals("平静", segments.get(0).originalTag());
        assertEquals("こんにちは", segments.get(0).japaneseText());
        assertEquals("hi", segments.get(0).followingText());
    }

    @Test
    @DisplayName("appendUserMessage：抽出临时段，正文里删掉（并挂成 {…} 尾巴，与 Rust 一致）")
    void extractTempInstruction() {
        SymbolParser.UserMessageOutcome outcome = parser.appendUserMessage("我今天有点紧张[!Temp!]语气温和点[/!Temp!]");

        assertEquals("我今天有点紧张\n{$语气温和点$}", outcome.main());
        assertEquals("$语气温和点$", outcome.temp());
    }

    @Test
    @DisplayName("appendUserMessage：时间感知固定关闭 → 没有『系统提醒: 时间』前缀")
    void timeSenseIsDisabled() {
        SymbolParser.UserMessageOutcome outcome = parser.appendUserMessage("开始吧");

        assertEquals("开始吧", outcome.main());
        assertNull(outcome.temp());
    }
}

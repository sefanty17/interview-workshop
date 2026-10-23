package cn.edu.zjut.interview.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code chat.reply_count} 追加句（规格 B4-BE §三，禁区：只能追加在正文要求段之后）。
 */
class PromptAssemblerReplyCountTest {

    private static final String AI_PROMPT = "你是一位严格的 Java 面试官。";

    @Test
    void defaultReplyCountKeepsPromptUnchanged() {
        String withoutCount = PromptAssembler.build(AI_PROMPT, "求职者", "小明", "示例对话");
        String withDefault = PromptAssembler.build(AI_PROMPT, "求职者", "小明", "示例对话", null,
                PromptAssembler.DEFAULT_REPLY_COUNT);

        assertEquals(withoutCount, withDefault, "默认值 3 不许改动已验证的 prompt");
        assertFalse(withDefault.contains("本次回复请输出"), "默认值 3 不追加句数提示");
    }

    @Test
    void nonDefaultReplyCountIsAppendedAfterBodySection() {
        String prompt = PromptAssembler.build(AI_PROMPT, "求职者", "小明", "示例对话", null, 1);

        assertTrue(prompt.contains("本次回复请输出 1 段台词。"), "要带上句数");
        // 位置：正文要求段（最后一段）之后，而不是插在中间
        int bodyIndex = prompt.indexOf(PromptConstants.DIALOG_FORMAT_PROMPT_2_BODY);
        int appendedIndex = prompt.indexOf("本次回复请输出 1 段台词。");
        assertTrue(bodyIndex > 0, "正文要求段必须在");
        assertTrue(appendedIndex > bodyIndex, "追加句必须在正文要求段之后");
        assertEquals(prompt.strip().length(), prompt.strip().lastIndexOf("本次回复请输出 1 段台词。")
                + "本次回复请输出 1 段台词。".length(), "追加句就是全文最后一句");
    }

    @Test
    void sevenSegmentsOrderIsUntouched() {
        String prompt = PromptAssembler.build(AI_PROMPT, "求职者", "小明", "示例对话", null, 6);

        int framing = prompt.indexOf("以下是我的对话格式提示");
        int format = prompt.indexOf(PromptConstants.DIALOG_FORMAT_PROMPT_CN);
        int example = prompt.indexOf(PromptConstants.DEFAULT_EXAMPLE_CN);
        int emotion = prompt.indexOf(PromptConstants.EMOTION_LIMIT_HEAD);
        int body = prompt.indexOf(PromptConstants.DIALOG_FORMAT_PROMPT_2_BODY);

        assertTrue(prompt.startsWith(AI_PROMPT), "人设段在第一位");
        assertTrue(framing > 0 && format > framing, "框架句 → 对话格式");
        assertTrue(example > format, "对话格式 → 默认示例");
        assertTrue(emotion > example, "默认示例 → 情绪限制");
        assertTrue(body > emotion, "情绪限制 → 正文要求");
    }
}

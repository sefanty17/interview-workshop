package cn.edu.zjut.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 信封格式逐字对齐 {@code utils/prompt.rs:284 PromptRole::build_prompt}。
 */
class PromptEnvelopeTest {

    @Test
    void plotEnvelopeIsExact() {
        assertEquals("{旁白: （接下来的剧情演绎提示：提问）}", PromptEnvelope.plot("提问"));
    }

    @Test
    void narratorEnvelopeIsExact() {
        assertEquals("{旁白: 面试开始}", PromptEnvelope.narrator("面试开始"));
    }

    @Test
    void narrationTagMatchesRustConstant() {
        assertEquals("{旁白:", PromptEnvelope.NARRATION_TAG);
    }
}

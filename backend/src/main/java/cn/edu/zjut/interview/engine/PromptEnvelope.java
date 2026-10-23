package cn.edu.zjut.interview.engine;

/**
 * 旁白 / 剧情提示信封，对应 {@code utils/prompt.rs:284 NARRATION_TAG} 与
 * {@code PromptRole::build_prompt}（{@code prompt.rs:289}）。
 *
 * <p>只搬中文分支里本项目会用到的两种角色：{@code Narrator}（旁白）与 {@code Plot}（剧情提示）。
 * 信封格式一个字符都不能改——{@code MemoryBuilder} 与模型都靠 {@code {旁白:}} 前缀识别提示行。
 */
public final class PromptEnvelope {

    /** 所有提示行的统一前缀标记（Rust: {@code NARRATION_TAG}）。 */
    public static final String NARRATION_TAG = "{旁白:";

    private PromptEnvelope() {
    }

    /** 剧情提示：{@code PromptRole::Plot}，ai_dialogue 的 prompt 用它包。 */
    public static String plot(String prompt) {
        return NARRATION_TAG + " （接下来的剧情演绎提示：" + prompt + "）}";
    }

    /** 旁白：{@code PromptRole::Narrator}，narration 事件的整段文本用它包。 */
    public static String narrator(String text) {
        return NARRATION_TAG + " " + text + "}";
    }
}

package cn.edu.zjut.interview.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static cn.edu.zjut.interview.prompt.PromptConstants.DEFAULT_EXAMPLE_CN;
import static cn.edu.zjut.interview.prompt.PromptConstants.DIALOG_FORMAT_PROMPT_2_BODY;
import static cn.edu.zjut.interview.prompt.PromptConstants.DIALOG_FORMAT_PROMPT_CN;
import static cn.edu.zjut.interview.prompt.PromptConstants.EMOTION_LIMIT_HEAD;
import static cn.edu.zjut.interview.prompt.PromptConstants.EXAMPLE_CUSTOM;

/**
 * 系统提示词拼装，对应 {@code utils/prompt.rs:183 sys_prompt_builder}（只做中文分支）。
 *
 * <p>拼接顺序与 Rust 完全一致：人设 → 框架句 → 对话格式 → 默认示例 → 自定义示例
 * → 情绪限制 → 正文规则；段间不加分隔符（各常量的前导 {@code \n} 就是分隔）。
 * 唯一的额外一段是 {@code interviewPlan}（本项目扩展，见规格 §3.3）。
 */
public final class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    /**
     * {@code chat.reply_count} 的默认值（与原版前端「正常（3~5 句）」档位对应）。
     * 等于默认值时不追加任何提示，保持已验证的 prompt 不变。
     */
    public static final int DEFAULT_REPLY_COUNT = 3;

    private PromptAssembler() {
    }

    /**
     * 框架句模板，对应 {@code prompt.rs:163-180 build_framing_prefix_cn}。
     * 逐字照抄，占位符由 {@code {u}} / {@code {c}} 改成 {@code {user}} / {@code {char}}。
     */
    private static final String FRAMING_PREFIX_CN = """

    以下是我的对话格式提示：
        首先，我会输出要和你对话的内容，然后在波浪号{}中的内容是对话系统给你的旁白环境提示或系统提示，比如：
        "{旁白: {user}在路上偶尔碰到了{char}，决定上前打个招呼}"
            你好呀{char}~
        {系统：时间：2025/6/1 0:29}"
        我也可能不给你发信息，仅包含系统提示。提示中也可能包含你的感知能力，比如：
        "{系统：时间：2025/5/20 13:14，你看到：{user}的电脑上正在玩Alice In Cradle}"
        系统提示的内容仅供参考，不是我真正对你说的话，更多是你感知到的信息和需要注意的事情，你无需对系统提示的内容回复相关信息。
        大括号波浪号中的内容偶尔也会包含别的角色的台词（格式为「角色名: 台词」），这些台词是你感知到的信息，你可以参考它们来回复我。
        总而言之大括号内的内容都是提示和感知内容，大括号外的则是我和你说的话。你需要根据提示和感知内容，以及我说的内容，来回复我。

        特别注意：旁白或者系统提示不是我说的话（这是由你感知或者程序自动补充的），而是你需要参考的事实依据或者需要演绎剧情的提示。不要把任何旁白内容当做是我的发言。
        如果你在屏幕识别中感知到了和自己相关的内容，比如自己出现在屏幕里与我对话，不要觉得意外，这是你和我通信的方式，这就是我们正常的交流方式。
    """;

    /**
     * 拼装完整 system prompt。对应 {@code prompt.rs:183 sys_prompt_builder} 的中文分支。
     *
     * @param aiPrompt        人设（{@code interviewer.persona_prompt}）
     * @param userName        用户显示名
     * @param characterName   面试官名
     * @param exampleDialogue 示例对话，可空（空串视同没有，与 Rust 的
     *                        {@code filter(|s| !s.is_empty())} 一致）
     * @param interviewPlan   【本项目扩展】已渲染好的面试编排段，可空
     * @return 完整 system prompt
     */
    public static String build(String aiPrompt,
                               String userName,
                               String characterName,
                               String exampleDialogue,
                               String interviewPlan) {
        return build(aiPrompt, userName, characterName, exampleDialogue, interviewPlan, DEFAULT_REPLY_COUNT);
    }

    /**
     * 同上，额外接受「本轮回复几句台词」（{@code chat.reply_count}）。
     *
     * <p>【本项目扩展】原版没有这个 key（前端「回复台词数量」只影响 UI 档位）。这里在
     * <b>正文要求段（{@link PromptConstants#DIALOG_FORMAT_PROMPT_2_BODY}）之后</b>追加一句
     * 「本次回复请输出 n 段台词。」——位置是硬约束：7 段的先后顺序一个字都不许动，
     * 这一段只能挂在最后。默认值 3 时不追加，避免影响已验证的 prompt。
     *
     * @param replyCount 本轮期望的台词段数；{@link #DEFAULT_REPLY_COUNT} 或非法值时不追加
     */
    public static String build(String aiPrompt,
                               String userName,
                               String characterName,
                               String exampleDialogue,
                               String interviewPlan,
                               int replyCount) {
        String prompt = aiPrompt == null ? "" : aiPrompt;

        // ---- 两个硬性短路（顺序与 Rust 一致：先日语翻译，再对话格式提示）----
        if (prompt.contains("日语翻译")) {
            log.warn("你使用的人物为旧版，不能使用实时翻译功能");
            return prompt;
        }
        if (prompt.contains("以下是我的对话格式提示")) {
            log.warn("你使用的人物为旧版，不进行拼接prompt");
            return prompt;
        }

        String framing = buildFramingPrefixCn(userName, characterName);
        // 对应 Rust: match example_cn { Some(s) => format!("{}\n{}", EXAMPLE_CUSTOM, s), None => String::new() }
        String example = (exampleDialogue == null || exampleDialogue.isEmpty())
                ? ""
                : EXAMPLE_CUSTOM + "\n" + exampleDialogue;

        StringBuilder out = new StringBuilder(prompt.length() + 4096);
        out.append(prompt);
        // 【本项目扩展】面试编排段：插在人设之后、框架句之前（规格 §3.2 第 3 步）。
        // 段落自带结尾 \n，框架句自带前导 \n，合起来正好空一行。
        if (interviewPlan != null && !interviewPlan.isEmpty()) {
            out.append('\n').append(interviewPlan);
        }
        out.append(framing);
        out.append(DIALOG_FORMAT_PROMPT_CN);
        out.append(DEFAULT_EXAMPLE_CN);
        out.append(example);
        out.append(EMOTION_LIMIT_HEAD);
        out.append(DIALOG_FORMAT_PROMPT_2_BODY);
        // 【本项目扩展】chat.reply_count：正文要求段之后追加一句（7 段顺序不变，只挂在末尾）。
        // 默认 3 段是不追加的，已验收的 prompt 长度与内容保持不变。
        if (replyCount > 0 && replyCount != DEFAULT_REPLY_COUNT) {
            out.append("\n本次回复请输出 ").append(replyCount).append(" 段台词。\n");
        }
        return out.toString();
    }

    /** 不带面试编排段的版本（纯 LingChat 行为，用于对照测试）。 */
    public static String build(String aiPrompt, String userName, String characterName, String exampleDialogue) {
        return build(aiPrompt, userName, characterName, exampleDialogue, null);
    }

    /**
     * 框架句，对应 {@code prompt.rs:163 build_framing_prefix_cn}。
     *
     * <p>Rust 用 {@code formatdoc!} 的 {@code {u}} / {@code {c}} 占位，这里改成
     * {@code {user}} / {@code {char}} 再 {@link String#replace}。
     * 常量里的 {@code {旁白: ...}} 等花括号是正文，替换不受影响。
     */
    public static String buildFramingPrefixCn(String userName, String characterName) {
        return FRAMING_PREFIX_CN
                .replace("{user}", userName == null ? "" : userName)
                .replace("{char}", characterName == null ? "" : characterName);
    }
}

package cn.edu.zjut.interview.engine;

/**
 * 引擎侧一轮生成的可调项，值来自后端设置（规格 B4-BE §三）。
 *
 * <p>剧本链路（{@code InterviewEngine} → {@code DialogueWriter}）和
 * {@code ChatService} 一样要读同一份设置：台词合并阈值与回复段数上限。
 * 引擎对象是手工 new 出来的（测试里也是），所以不塞 Spring bean，只传这一对小整数。
 *
 * @param mergeThreshold 台词合并阈值（{@code chat.merge_threshold}），0 = 不合并
 * @param replyCount     每次回复最多几句（{@code chat.reply_count}），0 = 不限制
 */
public record ChatTuning(int mergeThreshold, int replyCount) {

    /** 不带设置时的默认值：不合并、不限制（等价于接入设置之前的行为）。 */
    public static final ChatTuning DEFAULTS = new ChatTuning(0, 0);
}

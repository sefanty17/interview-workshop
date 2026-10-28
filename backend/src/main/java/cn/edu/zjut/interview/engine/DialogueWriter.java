package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmMessage;
import cn.edu.zjut.interview.stream.EmotionDictionary;
import cn.edu.zjut.interview.stream.EmotionSegment;
import cn.edu.zjut.interview.stream.SentenceSplitter;
import cn.edu.zjut.interview.stream.SymbolParser;

import java.util.List;

/**
 * 一次 AI 回复的「流 → 句 → 落库 → 推事件」写手。
 *
 * <p>对应 Rust {@code message_system::generator::MessageGenerator::process_message} 的生成侧行为
 * （{@code ai:thinking} 起始、分句、情绪解析、逐句 {@code ai:reply} 落库、结尾 {@code status:reset}），
 * 就是 {@code ChatService.ReplyPublisher} 的同一条链路，只是出口换成 {@link EventSink}。
 * 抽出来给 {@code ai_dialogue} 与 {@code free_dialogue} 共用，避免两处各写一份分句逻辑。
 */
public final class DialogueWriter {

    private static final double DURATION_BASE = 1.2;
    private static final double DURATION_PER_CHAR = 0.08;

    private final EngineContext ctx;
    private final Integer userMessageSeq;
    private final SentenceSplitter splitter;
    private final SymbolParser symbolParser = new SymbolParser();
    private SentenceSplitter.Sentence pending;
    private int published;

    private DialogueWriter(EngineContext ctx, Integer userMessageSeq) {
        this.ctx = ctx;
        this.userMessageSeq = userMessageSeq;
        // 合并阈值 / 回复段数同上位设置（chat.merge_threshold / chat.reply_count）
        this.splitter = new SentenceSplitter(ctx.tuning().mergeThreshold(), ctx.tuning().replyCount());
    }

    /**
     * 阻塞跑完一次生成：{@code llmClient.stream} → 每句 {@code sink.reply} + 落 assistant 台词。
     *
     * @return 真正推出去的台词条数
     */
    public static int writeTurn(EngineContext ctx, List<LlmMessage> messages, Integer userMessageSeq) {
        return new DialogueWriter(ctx, userMessageSeq).run(messages);
    }

    private int run(List<LlmMessage> messages) {
        // 同 Rust generator：流启动前 emit_thinking(true)，一轮结束（或异常）后 status:reset
        ctx.sink().thinking();
        try {
            ctx.llmClient().stream(messages, chunk -> {
                for (SentenceSplitter.Sentence sentence : splitter.feed(chunk)) {
                    offer(sentence);
                }
            });
            for (SentenceSplitter.Sentence sentence : splitter.finish()) {
                offer(sentence);
            }
            flush();
        } finally {
            // 「一次 AI 回复生成结束」就复位，不是整场结束——后面可能还有别的事件
            ctx.sink().statusReset();
        }
        return published;
    }

    /** 压一句：isFinal 只有流结束才知道，因此总是把上一句按 isFinal=false 推出去。 */
    private void offer(SentenceSplitter.Sentence sentence) {
        if (pending != null) {
            publish(pending, false);
        }
        pending = sentence;
    }

    private void flush() {
        if (pending != null) {
            publish(pending, true);
            pending = null;
        }
    }

    private void publish(SentenceSplitter.Sentence sentence, boolean isFinal) {
        String original = sentence.text();
        List<EmotionSegment> segments = symbolParser.parse(original);
        EmotionSegment segment = segments.isEmpty() ? fallbackSegment(original) : segments.get(0);
        if (segment.followingText().isEmpty() && segment.motionText().isEmpty()) {
            // 纯标签 / 纯空行，没有可显示的正文
            return;
        }

        ctx.saveMessage(InterviewMessage.ROLE_ASSISTANT, segment.followingText(), "text",
                segment.originalTag(), segment.predicted(), segment.motionText());

        Interviewer interviewer = ctx.interviewer();
        String name = interviewer == null ? "" : interviewer.getName();
        String role = interviewer == null ? "" : interviewer.getDisplayRole();

        ReplyEvent event = new ReplyEvent(
                ReplyEvent.TYPE,
                isFinal,
                duration(segment.followingText()),
                segment.followingText(),
                original,
                segment.predicted(),
                segment.originalTag(),
                segment.motionText(),
                name,
                name,
                role,
                userMessageSeq,
                ctx.senderRoleId(InterviewMessage.ROLE_ASSISTANT, "text"),
                // 剧本链路本批不接语音：落库走 ctx.saveMessage，seq 由引擎内部自增、这里取不到，
                // 故填 null（前端 ai:voice 对句只在自由对话链路生效）
                null);
        ctx.sink().reply(event);
        published++;
    }

    /** 模型没写 {@code 【情绪】} 时的兜底：剥标签 + 情绪「正常」（同 ChatService）。 */
    private EmotionSegment fallbackSegment(String original) {
        String cleaned = original
                .replaceAll("【[^】]*】", "")
                .replaceAll("（[^）]*）", "")
                .replaceAll("<[^>]*>", "")
                .strip();
        String prediction = EmotionDictionary.getInstance().predict(null);
        return new EmotionSegment(1, null, cleaned, "", "", prediction);
    }

    /** 展示时长：{@code max(1.2, 字数 * 0.08)}，保留 1 位小数（规格 §1.2）。 */
    private static double duration(String message) {
        int length = message == null ? 0 : message.length();
        double raw = Math.max(DURATION_BASE, length * DURATION_PER_CHAR);
        return Math.round(raw * 10) / 10.0;
    }
}

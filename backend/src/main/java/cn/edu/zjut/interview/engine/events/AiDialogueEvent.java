package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.engine.DialogueWriter;
import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.PromptEnvelope;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.llm.LlmMessage;
import cn.edu.zjut.interview.prompt.MemoryBuilder;
import cn.edu.zjut.interview.prompt.PromptAssembler;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话事件，对应 {@code script_engine/events/ai_dialogue_event.rs}。
 *
 * <p>原版流程：解析出角色 → 若带 prompt，把 {@code PromptRole::Plot} 信封包好的提示作为一条
 * 「user + display_name=旁白」台词落库 → 交给 {@code MessageGenerator} 用整段记忆生成回复。
 *
 * <p>Java 侧同样三步，只是生成侧换成
 * {@code MemoryBuilder → LlmClient.stream → SentenceSplitter → SymbolParser → 落库 + reply}：
 * <ol>
 *   <li>plot 提示先落库（{@code msg_type=plot}），这样它会自然进入 {@link MemoryBuilder} 的 user 段</li>
 *   <li>记忆由 {@link MemoryBuilder} 从库里重建（system 串在建会话时就已落库，不重拼）</li>
 *   <li>逐句落 assistant 台词并推 {@code reply}</li>
 * </ol>
 */
public class AiDialogueEvent implements ScriptEvent {

    public static final String TYPE = "ai_dialogue";

    private final MemoryBuilder memoryBuilder = new MemoryBuilder();

    @Override
    public String execute(EngineContext ctx) throws Exception {
        String prompt = ctx.text("prompt");
        List<InterviewMessage> history = ctx.messageRepository().findBySessionIdOrderBySeqAsc(ctx.sessionId());

        Integer userSeq = lastUserSeq(history);
        if (!prompt.isEmpty()) {
            // 原版：prompt 先作为一条旁白台词注入，再生成回复
            userSeq = ctx.saveMessage(InterviewMessage.ROLE_USER, PromptEnvelope.plot(prompt), "plot",
                    null, null, null);
            history = ctx.messageRepository().findBySessionIdOrderBySeqAsc(ctx.sessionId());
        }

        List<LlmMessage> messages = withSystem(ctx, memoryBuilder.build(history));
        // 生成侧复用 EngineContext 的落库/推流出口
        DialogueWriter.writeTurn(ctx, messages, userSeq);
        return null;
    }

    /**
     * 保证首条是 system：正常场次建会话时已落 system 串，这里只兜老数据。
     *
     * <p>兜底时复用 {@link PromptAssembler}（人设 + 框架句 + 格式 + 示例 + 情绪限制），
     * 与原版「没有 system 台词就没有人设」的区别是：本项目不允许没有 system 的会话跑下去。
     */
    private List<LlmMessage> withSystem(EngineContext ctx, List<LlmMessage> messages) {
        if (!messages.isEmpty() && LlmMessage.ROLE_SYSTEM.equals(messages.get(0).role())) {
            return messages;
        }
        Interviewer interviewer = ctx.interviewer();
        List<LlmMessage> withSystem = new ArrayList<>(messages.size() + 1);
        withSystem.add(LlmMessage.system(PromptAssembler.build(
                interviewer == null ? "" : interviewer.getPersonaPrompt(),
                ctx.playerName(),
                interviewer == null ? "" : interviewer.getName(),
                interviewer == null ? null : interviewer.getExampleDialogue(),
                null)));
        withSystem.addAll(messages);
        return withSystem;
    }

    private Integer lastUserSeq(List<InterviewMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (InterviewMessage.ROLE_USER.equals(history.get(i).getRole())) {
                return history.get(i).getSeq();
            }
        }
        return null;
    }
}

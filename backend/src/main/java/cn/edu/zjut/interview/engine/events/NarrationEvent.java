package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.PromptEnvelope;
import cn.edu.zjut.interview.engine.ScriptEvent;

/**
 * 旁白事件，对应 {@code script_engine/events/narration_event.rs}。
 *
 * <p>原版：把 text 按 {@code \n} 切开、过滤空行后逐行 emit 给前端；整段（保留换行）
 * 用 {@code {旁白: …}} 信封落一条 {@code LineAttribute::User} 台词。这里逐行
 * {@code sink.narration}，落库用 {@code msg_type=narration} 承担原版 {@code display_name="旁白"}。
 */
public class NarrationEvent implements ScriptEvent {

    public static final String TYPE = "narration";

    @Override
    public String execute(EngineContext ctx) {
        String text = ctx.text("text");

        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                ctx.sink().narration(line);
            }
        }

        ctx.saveMessage(InterviewMessage.ROLE_USER, PromptEnvelope.narrator(text), "narration",
                null, null, null);
        return null;
    }
}

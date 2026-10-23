package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.ScriptFunctions;
import cn.edu.zjut.interview.stream.EmotionSegment;
import cn.edu.zjut.interview.stream.SymbolParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 固定台词事件，对应 {@code script_engine/events/dialog_event.rs}。
 *
 * <p><b>注意它不发 {@code script:dialog}</b>：原版逐行把 text 交给 {@code consume_sentence}
 * （情绪解析 → 富化 → 写台词），每条都 emit {@code ai:reply}；解析不到【情绪】分段或富化
 * 失败时回退 {@code emit_plain_reply}，同样只发 {@code ai:reply}（{@code dialog_event.rs:101}）。
 *
 * <p>原版先 {@code current_role_id = Some(role_id)} 再组装台词；本项目台词表没有角色列，
 * 角色只体现在 {@code ReplyEvent.character}（事件里的 {@code character}，缺省 {@code MAIN}）。
 *
 * <p>与原版的差异仅在「富化」：本项目没有翻译 / TTS，{@code consume_sentence} 的富化步骤
 * 本就不存在，所以只保留「情绪解析 → 落库 → 推 {@code ai:reply}」这一段。
 */
public class DialogEvent implements ScriptEvent {

    /**
     * 事件类型串，与 {@code dialog_event.rs:227 event_type()} 一致是 {@code "dialogue"}
     * （剧本 YAML 里也写 {@code type: dialogue}）。类名按规格叫 {@code DialogEvent}。
     */
    public static final String TYPE = "dialogue";

    private static final Logger log = LoggerFactory.getLogger(DialogEvent.class);

    private final SymbolParser symbolParser = new SymbolParser();

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        String character = event != null && event.path("character").isTextual()
                ? event.get("character").asText()
                : "MAIN";
        // 占位符已由引擎在执行前替换；这里再走一次与原版 dialog_event.rs:172 对齐（幂等）
        String text = ScriptFunctions.replacePlaceholder(ctx.text("text"), ctx.playerName());

        Interviewer interviewer = ctx.interviewer();
        String displayName = textual(event, "displayName",
                interviewer == null ? "" : interviewer.getName());
        String displaySubtitle = textual(event, "displaySubtitle", "");
        String emotion = textual(event, "emotion", "");
        Double duration = ScriptEvent.parseDuration(event);

        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            List<EmotionSegment> segments = symbolParser.parse(line);
            if (segments.isEmpty()) {
                // 解析不到【情绪】分段：回退纯文本，保留已有纯文本剧本
                log.warn("[DialogEvent] dialogue 文本未解析到【情绪】分段，回退纯文本: {}", line);
                emitReply(ctx, character, line, line, emotion, "", "", displayName, displaySubtitle, duration);
                continue;
            }
            EmotionSegment segment = segments.get(0);
            emitReply(ctx, character, segment.followingText(), line, segment.predicted(),
                    segment.originalTag(), segment.motionText(), displayName, displaySubtitle, duration);
        }
        return null;
    }

    /** 落一条 assistant 台词并推 {@code ai:reply}（原版 {@code emit_plain_reply} 的同一条链路）。 */
    private void emitReply(EngineContext ctx, String character, String message, String originalMessage,
                           String emotion, String originalTag, String motionText,
                           String displayName, String displaySubtitle, Double duration) {
        ctx.saveMessage(InterviewMessage.ROLE_ASSISTANT, message, "text", originalTag, emotion, motionText);
        ctx.sink().reply(new ReplyEvent(
                ReplyEvent.TYPE,
                true,
                duration == null ? -1.0 : duration,
                message,
                originalMessage,
                emotion,
                originalTag,
                motionText,
                character,
                displayName,
                displaySubtitle,
                null,
                ctx.senderRoleId(InterviewMessage.ROLE_ASSISTANT, "text")));
    }

    /** 原版 {@code .and_then(as_str).unwrap_or(默认值)}：字段存在且是字符串就用它，否则用默认值。 */
    private static String textual(JsonNode event, String field, String defaultValue) {
        if (event == null) {
            return defaultValue;
        }
        JsonNode node = event.get(field);
        return node != null && node.isTextual() ? node.asText() : defaultValue;
    }
}

package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.PlayerPayload;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 玩家台词事件，对应 {@code script_engine/events/player_event.rs}。
 *
 * <p>原版：emit {@code script:player}（{@code PlayerPayload{text, displayName?, duration?}}）
 * 后落一条 {@code LineAttribute::User} 台词。本项目同样两步，只是台词表没有
 * {@code display_name} 列，展示名只走 SSE。
 *
 * <p>{@code displayName} 缺省取玩家昵称（原版 {@code player.user_name}），
 * 写死空串也算作者显式指定。
 */
public class PlayerEvent implements ScriptEvent {

    public static final String TYPE = "player";

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        String text = ctx.text("text");
        JsonNode nameNode = event == null ? null : event.get("displayName");
        String displayName = nameNode != null && nameNode.isTextual()
                ? nameNode.asText()
                : ctx.playerName();

        ctx.sink().player(new PlayerPayload(text, displayName, ScriptEvent.parseDuration(event)));
        // 文本里的占位符已由引擎在执行前替换（events_handler resolve_placeholders）
        ctx.saveMessage(InterviewMessage.ROLE_USER, text, "text", null, null, null);
        return null;
    }
}

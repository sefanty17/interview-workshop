package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.ModifyCharacterPayload;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * 角色变更事件，对应 {@code script_engine/events/modify_character_event.rs}。
 *
 * <p>搬原版的三支：
 * <ol>
 *   <li>{@code character} 定位（缺省 {@code MAIN}；{@code MAIN} 解析成本场面试官）</li>
 *   <li>{@code action}：{@code show_character} 上场、{@code hide_character} 下场，
 *       其余动作静默忽略（原版 {@code _ => {}}）</li>
 *   <li>{@code emotion}：写进 {@code variable_info.forcedEmotion}，供后续轮次沿用</li>
 * </ol>
 *
 * <p>原版的换装（{@code clothes}）与 {@code perceive} 未迁：本项目没有立绘与感知集合。
 */
public class ModifyCharacterEvent implements ScriptEvent {

    public static final String TYPE = "modify_character";

    /** 变量表里记住强制情绪的键。 */
    public static final String FORCED_EMOTION_KEY = "forcedEmotion";

    public static final String ACTION_SHOW = "show_character";
    public static final String ACTION_HIDE = "hide_character";

    @Override
    public String execute(EngineContext ctx) {
        // 原版 from_event_data：character 缺省 "MAIN"
        String character = ctx.text("character");
        if (character.isEmpty()) {
            character = EngineContext.MAIN_ROLE;
        }
        String action = ctx.text("action");
        String emotion = ctx.text("emotion");

        Set<String> presentRoles = ctx.presentRoles();
        switch (action) {
            case ACTION_SHOW -> presentRoles.add(character);
            case ACTION_HIDE -> presentRoles.remove(character);
            default -> {
                // 原版 _ => {}：不认识的动作不改舞台
            }
        }

        if (!emotion.isEmpty()) {
            ObjectNode vars = ctx.vars();
            if (vars != null) {
                vars.put(FORCED_EMOTION_KEY, emotion);
            }
        }

        if (emotion.isEmpty() && action.isEmpty()) {
            // 既没情绪也没动作：原版仍会 emit 一个空 payload，这里跳过，避免空帧刷屏
            return null;
        }

        ctx.sink().character(new ModifyCharacterPayload(
                ctx.roleId(character),
                emotion.isEmpty() ? null : emotion,
                action.isEmpty() ? null : action,
                ctx.presentRoleIds()));
        return null;
    }
}

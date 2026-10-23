package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.BackgroundEffectPayload;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 背景特效事件，对应 {@code script_engine/events/background_effect_event.rs}。
 *
 * <p>原版：effect 缺省 {@code "none"}，写进 {@code game_status.background_effect}
 * （不在内置清单里就只是一种「清空」），emit {@code script:background-effect}
 * （{@code BackgroundEffectPayload{effect, duration?}}）。
 *
 * <p>特效值原样透传（前端不认识就当清空），但 {@code from_event_data} 会为
 * 「大小写写错」与「完全未知」两种情况各打一条 warn——两个存量剧本写
 * {@code starfield} / {@code Starfield} 时曾经毫无提示地没有粒子效果。
 * {@link #KNOWN_EFFECTS} 照抄 {@code events/known_effects.rs}（由前端粒子注册表生成）。
 */
public class BackgroundEffectEvent implements ScriptEvent {

    public static final String TYPE = "background_effect";

    /** 前端真正渲染的特效名（{@code known_effects.rs:17-30}），大小写敏感。 */
    public static final List<String> KNOWN_EFFECTS = List.of(
            "StarField", "Sakura", "Fireworks", "BA", "Fireflies", "MeteorShower",
            "Drizzle", "Rain", "Thunderstorm", "Snow", "Blizzard", "Fog");

    /** 明确表示「没有特效」的值，不该被 warn（{@code CLEARING_EFFECTS}）。 */
    private static final List<String> CLEARING_EFFECTS = List.of("none", "None", "");

    private static final Logger log = LoggerFactory.getLogger(BackgroundEffectEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        String effect = event != null && event.hasNonNull("effect")
                ? event.get("effect").asText()
                : "none";

        if (!CLEARING_EFFECTS.contains(effect) && !KNOWN_EFFECTS.contains(effect)) {
            String hint = KNOWN_EFFECTS.stream()
                    .filter(known -> known.equalsIgnoreCase(effect))
                    .findFirst()
                    .orElse(null);
            if (hint != null) {
                log.warn("[BackgroundEffectEvent] 特效名 '{}' 大小写不匹配，前端不会渲染；应为 '{}'", effect, hint);
            } else {
                log.warn("[BackgroundEffectEvent] 未知特效 '{}'，将清空当前特效；可用值: {}", effect, KNOWN_EFFECTS);
            }
        }

        ctx.sink().backgroundEffect(new BackgroundEffectPayload(effect, ScriptEvent.parseDuration(event)));
        return null;
    }
}

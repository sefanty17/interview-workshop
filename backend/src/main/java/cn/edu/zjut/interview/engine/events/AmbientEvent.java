package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.AmbientPayload;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 环境音事件，对应 {@code script_engine/events/ambient_event.rs}。
 *
 * <p>循环持续的场景音（雨声、风声、人群嘈杂），与 BGM 同时共存。字段缺省值照原版：
 * {@code volume=100.0}、{@code loop=true}、{@code stop=false}、{@code fade=true}；
 * emit {@code script:ambient}（{@code AmbientPayload{ambientPath, volume, loop, stop, fade, duration?}}）。
 *
 * <p>{@code stop=true} 是停止指令，此时不需要解析路径，直接发出（原版同样提前 return）。
 * 本项目路径原样下发。
 */
public class AmbientEvent implements ScriptEvent {

    public static final String TYPE = "ambient";

    private static final Logger log = LoggerFactory.getLogger(AmbientEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        String ambientPath = ctx.text("ambientPath");
        double volume = event != null && event.path("volume").isNumber() ? event.get("volume").asDouble() : 100.0;
        boolean loop = event == null || event.path("loop").asBoolean(true);
        boolean stop = event != null && event.path("stop").asBoolean(false);
        boolean fade = event == null || event.path("fade").asBoolean(true);
        Double duration = ScriptEvent.parseDuration(event);

        if (stop) {
            ctx.sink().ambient(new AmbientPayload(ambientPath, volume, loop, true, fade, duration));
            log.info("[AmbientEvent] 停止环境音: {}", ambientPath);
            return null;
        }

        ctx.sink().ambient(new AmbientPayload(ambientPath, volume, loop, false, fade, duration));
        log.info("[AmbientEvent] 环境音: {} (音量: {}, 循环: {})", ambientPath, volume, loop);
        return null;
    }
}

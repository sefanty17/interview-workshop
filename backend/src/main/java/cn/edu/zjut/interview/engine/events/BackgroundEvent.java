package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.BackgroundPayload;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 背景切换事件，对应 {@code script_engine/events/background_event.rs}。
 *
 * <p>原版：{@code imagePath} 经 {@code resolve_script_media} 解析 →
 * {@code set_background_override} 记临时背景（随存档恢复、读档时盖过场景背景）→
 * emit {@code script:background}（{@code BackgroundPayload{imagePath, transition, duration?}}，
 * {@code transition} 缺省 1.0）。
 *
 * <p>本项目没有剧本目录与存档快照，路径原样下发，也不保存背景覆盖状态。
 */
public class BackgroundEvent implements ScriptEvent {

    public static final String TYPE = "background";

    private static final Logger log = LoggerFactory.getLogger(BackgroundEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        String imagePath = ctx.text("imagePath");
        double transition = event != null && event.path("transition").isNumber()
                ? event.get("transition").asDouble()
                : 1.0;

        ctx.sink().background(new BackgroundPayload(imagePath, transition, ScriptEvent.parseDuration(event)));
        log.info("[BackgroundEvent] 背景切换: {}", imagePath);
        return null;
    }
}

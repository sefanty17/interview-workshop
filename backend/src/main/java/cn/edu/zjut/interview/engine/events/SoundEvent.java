package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.SoundPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 音效事件，对应 {@code script_engine/events/sound_event.rs}。
 *
 * <p>原版：{@code soundPath} 经 {@code resolve_script_media} 解析 → emit
 * {@code script:sound}（{@code SoundPayload{soundPath, duration?}}）→ 不保存任何状态
 * （注释原话：「emits a sound to the frontend without storing state」）。
 */
public class SoundEvent implements ScriptEvent {

    public static final String TYPE = "sound";

    private static final Logger log = LoggerFactory.getLogger(SoundEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        String soundPath = ctx.text("soundPath");

        ctx.sink().sound(new SoundPayload(soundPath, ScriptEvent.parseDuration(ctx.event())));
        log.info("[SoundEvent] SFX: {}", soundPath);
        return null;
    }
}

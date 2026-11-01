package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.SoundPayload;
import cn.edu.zjut.interview.media.MediaStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 音效事件，对应 {@code script_engine/events/sound_event.rs}。
 *
 * <p>原版：{@code soundPath} 经 {@code resolve_script_media} 解析 → emit
 * {@code script:sound}（{@code SoundPayload{soundPath, duration?}}）→ 不保存播放状态
 * （注释原话：「emits a sound to the frontend without storing state」）。
 *
 * <p>issue #13 把「最后一条音效」记进 {@code media_state} 供排查，刷新时<b>不重放</b>
 * （一次性音效重放会凭空多一声）。
 */
public class SoundEvent implements ScriptEvent {

    public static final String TYPE = "sound";

    private static final Logger log = LoggerFactory.getLogger(SoundEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        String soundPath = ctx.text("soundPath");

        MediaStateStore.applySound(ctx.session(), ctx.objectMapper(), soundPath);
        ctx.sink().sound(new SoundPayload(soundPath, ScriptEvent.parseDuration(ctx.event())));
        log.info("[SoundEvent] SFX: {}", soundPath);
        return null;
    }
}

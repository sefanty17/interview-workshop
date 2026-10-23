package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.payload.MusicPayload;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGM 事件，对应 {@code script_engine/events/music_event.rs}。
 *
 * <p>原版：{@code musicPath} 经 {@code resolve_script_media} 解析 → 写
 * {@code game_status.background_music} → emit {@code script:music}
 * （{@code MusicPayload{musicPath, playbackSpeed?, duration?}}）。
 *
 * <p>本项目路径原样下发（前端用 HTMLAudio 播放），不保存 BGM 状态。
 */
public class MusicEvent implements ScriptEvent {

    public static final String TYPE = "music";

    private static final Logger log = LoggerFactory.getLogger(MusicEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        String musicPath = ctx.text("musicPath");
        Double playbackSpeed = event != null && event.path("playbackSpeed").isNumber()
                ? event.get("playbackSpeed").asDouble()
                : null;

        ctx.sink().music(new MusicPayload(musicPath, playbackSpeed, ScriptEvent.parseDuration(event)));
        log.info("[MusicEvent] BGM: {}", musicPath);
        return null;
    }
}

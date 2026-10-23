package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.engine.payload.AmbientPayload;
import cn.edu.zjut.interview.engine.payload.BackgroundEffectPayload;
import cn.edu.zjut.interview.engine.payload.BackgroundPayload;
import cn.edu.zjut.interview.engine.payload.ChoicePayload;
import cn.edu.zjut.interview.engine.payload.MusicPayload;
import cn.edu.zjut.interview.engine.payload.ModifyCharacterPayload;
import cn.edu.zjut.interview.engine.payload.PlayerPayload;
import cn.edu.zjut.interview.engine.payload.PresentPicPayload;
import cn.edu.zjut.interview.engine.payload.SoundPayload;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link EventSink} 的 SSE 实现：事件名沿用 LingChat 原名，不另起裸名。
 *
 * <p>台词 {@code ai:reply} 与脚本事件取自 Rust 侧两张常量表：
 * {@code message_system/responses.rs::event_names}（{@code ai:reply / ai:thinking /
 * ai:error / status:reset}）与
 * {@code game_system/script_engine/responses.rs::event_names}（{@code script:narration /
 * script:player / script:background / script:background-effect / script:music /
 * script:sound / script:ambient / script:present-pic / script:choice /
 * script:input / script:modify-character / script:chapter-change / script:progress /
 * script:end}）。{@code ai:reply} 的 data 仍是 {@link ReplyEvent}，与前端
 * {@code api/chat.ts} 的消费格式一字不差。
 *
 * <p>展示类事件的 data 直接用 {@code engine.payload} 下的记录，字段名与
 * {@code responses.rs} 的 struct（{@code rename_all = "camelCase"}）一一对应。
 */
public class SseEventSink implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(SseEventSink.class);

    private final SseEmitter emitter;

    public SseEventSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void reply(ReplyEvent event) {
        send("ai:reply", event);
    }

    @Override
    public void thinking() {
        // ThinkingResponse::new(true)：{"type":"thinking","isThinking":true,"duration":0.0}
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "thinking");
        payload.put("isThinking", true);
        payload.put("duration", 0.0);
        send("ai:thinking", payload);
    }

    @Override
    public void statusReset() {
        // StatusResetResponse::new("input")：{"type":"status_reset","status":"input"}
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "status_reset");
        payload.put("status", "input");
        send("status:reset", payload);
    }

    @Override
    public void narration(String text) {
        send("script:narration", Map.of("text", text == null ? "" : text));
    }

    @Override
    public void input(String hint) {
        send("script:input", Map.of("hint", hint == null ? "" : hint));
    }

    @Override
    public void character(ModifyCharacterPayload payload) {
        send("script:modify-character", payload);
    }

    @Override
    public void chapter(String code, String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", name);
        send("script:chapter-change", payload);
    }

    @Override
    public void progress(String chapter, int eventIndex, int lineCount, JsonNode vars) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chapter", chapter);
        payload.put("eventIndex", eventIndex);
        payload.put("lineCount", lineCount);
        payload.put("vars", vars);
        send("script:progress", payload);
    }

    @Override
    public void finished() {
        send("script:end", Map.of());
    }

    // ───────────── 规格 B7：8 个展示类事件（名字照 responses.rs:14-28） ─────────────

    @Override
    public void player(PlayerPayload payload) {
        send("script:player", payload);
    }

    @Override
    public void choice(ChoicePayload payload) {
        send("script:choice", payload);
    }

    @Override
    public void background(BackgroundPayload payload) {
        send("script:background", payload);
    }

    @Override
    public void backgroundEffect(BackgroundEffectPayload payload) {
        send("script:background-effect", payload);
    }

    @Override
    public void music(MusicPayload payload) {
        send("script:music", payload);
    }

    @Override
    public void sound(SoundPayload payload) {
        send("script:sound", payload);
    }

    @Override
    public void ambient(AmbientPayload payload) {
        send("script:ambient", payload);
    }

    @Override
    public void presentPic(PresentPicPayload payload) {
        send("script:present-pic", payload);
    }

    /**
     * 引擎抛异常时由 Controller 调这个：先推 {@code ai:error}，再推 {@code status:reset}。
     *
     * <p>同 Rust {@code events::emit_error}——报错的同时把前端状态复位，否则界面停在等待态。
     */
    public void error(String message) {
        send("ai:error", Map.of("message", message == null ? "" : message));
        statusReset();
    }

    private void send(String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("[ScriptEngine] SSE 写入失败（连接可能已断开）：{}", e.toString());
        }
    }
}

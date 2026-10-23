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

/**
 * 引擎往外推事件的出口（规格 §3.5）。
 *
 * <p>Rust 侧对应 {@code message_system::events::emit} 的 {@code SCRIPT_*} 事件：
 * 原版直接 emit 到 Tauri 前端，这里抽象成接口，SSE 实现见 {@link SseEventSink}，
 * 单测里可以换成收集列表的实现而不碰网络。
 *
 * <p>规格 B7 的 8 个展示类事件用 {@code default} 空实现：与 {@link #thinking()} /
 * {@link #statusReset()} 同策略——非 SSE 出口（测试桩、命令行）可以只实现自己关心的方法。
 */
public interface EventSink {

    /** 一条 AI 台词（复用 {@link ReplyEvent}，字段与现有 {@code ai:reply} 完全一致）。 */
    void reply(ReplyEvent event);

    /** 一次 AI 生成开始：{@code ai:thinking}（isThinking=true）。非 SSE 出口可忽略。 */
    default void thinking() {
    }

    /** 一次 AI 生成结束（含出错）：{@code status:reset}，把前端从等待态放回输入态。 */
    default void statusReset() {
    }

    /** 一行旁白（narration 事件按 {@code \n} 切开后逐行推）。 */
    void narration(String text);

    /** 请求用户输入（hint 为输入框提示，可能为空串）。 */
    void input(String hint);

    /** 角色变更（modify_character 事件）：情绪 + 上场/下场 + 变更后的在场角色。 */
    void character(ModifyCharacterPayload payload);

    /** 进入新章节。 */
    void chapter(String code, String name);

    /** 阅读锚点：章节 + 事件下标 + 当前台词条数 + 剧本变量。 */
    void progress(String chapter, int eventIndex, int lineCount, JsonNode vars);

    /** 剧本结束。 */
    void finished();

    // ───────────── 规格 B7：展示类事件（事件名照 responses.rs:14-28） ─────────────

    /** {@code script:player}：玩家侧展示行。 */
    default void player(PlayerPayload payload) {
    }

    /** {@code script:choice}：分支选项。 */
    default void choice(ChoicePayload payload) {
    }

    /** {@code script:background}：换背景。 */
    default void background(BackgroundPayload payload) {
    }

    /** {@code script:background-effect}：背景特效层。 */
    default void backgroundEffect(BackgroundEffectPayload payload) {
    }

    /** {@code script:music}：BGM。 */
    default void music(MusicPayload payload) {
    }

    /** {@code script:sound}：音效。 */
    default void sound(SoundPayload payload) {
    }

    /** {@code script:ambient}：环境音（可循环，与 BGM 共存）。 */
    default void ambient(AmbientPayload payload) {
    }

    /** {@code script:present-pic}：全屏图片。 */
    default void presentPic(PresentPicPayload payload) {
    }
}

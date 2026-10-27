package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * VAD 事件的 HTTP 契约，对齐上游 {@code api/services/asr.ts::VadEvent}：
 * {@code type} 是 snake_case 判别串，其余字段按类型可选。
 *
 * <p>上游事件走 Tauri 通道（{@code asr://speech_started} 等）推送，浏览器侧没有这条通道，
 * 所以这里改成随响应体同步返回（差异 C）。字段名与取值必须与上游一致，否则 V3 搬前端时
 * 拿到的事件分发不到对应分支。
 *
 * <p>{@link JsonInclude.Include#NON_NULL} 必须加：上游 TS 类型把 {@code silence_ms} 声明为
 * 可选，序列化出 {@code "silence_ms": null} 会让前端用 {@code "silence_ms" in ev}
 * 之类的判断拿到 true，误以为静音计时有值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VadEventView(
        /** speech_started / silence_started / turn_candidate / turn_sealed */
        @JsonProperty("type") String type,
        /** 仅 silence_started / turn_candidate 有值，其余 null（不输出） */
        @JsonProperty("silence_ms") Long silenceMs,
        /** 仅 turn_sealed */
        @JsonProperty("start_frame") Long startFrame,
        /** 仅 turn_sealed */
        @JsonProperty("end_frame") Long endFrame) {

    /** 事件类型串：与上游 {@code VadEvent} 的 {@code rename_all = "snake_case"} 逐字一致。 */
    public static final String SPEECH_STARTED = "speech_started";
    public static final String SILENCE_STARTED = "silence_started";
    public static final String TURN_CANDIDATE = "turn_candidate";
    public static final String TURN_SEALED = "turn_sealed";

    /**
     * 状态机事件 → HTTP 视图。
     *
     * <p>映射严格照上游 {@code vad.rs:216-236}：
     * <ul>
     *   <li>{@code SpeechStart} → {@code speech_started}，{@code silence_ms} 为 null</li>
     *   <li>{@code SilenceStart} → {@code silence_started}，{@code silence_ms} = <b>0</b>
     *       （上游 {@code vad.rs:222} 写死 0，这里照抄，不改成实际静音帧数）</li>
     *   <li>{@code TurnCandidate} → {@code turn_candidate}，{@code silence_ms = frames × 30}</li>
     *   <li>{@code TurnSealed} → {@code turn_sealed}，带起止帧</li>
     * </ul>
     */
    public static VadEventView from(VadSegmenter.SegmentEvent event) {
        if (event instanceof VadSegmenter.SpeechStart) {
            return new VadEventView(SPEECH_STARTED, null, null, null);
        }
        if (event instanceof VadSegmenter.SilenceStart) {
            return new VadEventView(SILENCE_STARTED, 0L, null, null);
        }
        if (event instanceof VadSegmenter.TurnCandidate candidate) {
            // 上游用 30ms/帧换算成毫秒回给前端（silence_frames * 30），不写成 32
            // ——同一个 vad_silence_ms 设置值两边换算必须得同一个毫秒数。
            return new VadEventView(TURN_CANDIDATE, candidate.silenceFrames() * EnergyVad.FRAME_MS,
                    null, null);
        }
        VadSegmenter.TurnSealed sealed = (VadSegmenter.TurnSealed) event;
        return new VadEventView(TURN_SEALED, null, sealed.startFrame(), sealed.endFrame());
    }
}

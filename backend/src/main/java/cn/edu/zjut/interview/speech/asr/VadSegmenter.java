package cn.edu.zjut.interview.speech.asr;

import java.util.ArrayList;
import java.util.List;

/**
 * VAD 切分状态机，1:1 移植上游 {@code ai_service/asr/vad_segmenter.rs}。
 *
 * <p>它只消费「每帧语音概率」，与概率怎么算出来完全解耦：上游由 Silero 模型给 prob，
 * 本项目由 {@link EnergyVad} 的能量门控给 prob。事件语义、帧率约定、阈值行为全部一致，
 * 所以移植这一层就够了，不需要动状态机里的任何判断。
 *
 * <p>帧率约定：每帧 30ms（16kHz × 512 samples）。<b>帧号即时间</b>，所以这里的
 * {@code frame} 计数同时是「第几帧」与「第 30ms 个刻度」——一旦某处按 32ms 换算，
 * 同一个 {@code vad_silence_ms} 在两边的触发时刻就对不上。
 *
 * <p>为什么逐行照抄、不「优化」成更少的状态：候选/确认两段静音计时（27 帧 / 33 帧）
 * 加上超长强制切段，四条路径互相有交叉（语音恢复要清候选、超长切段要立刻重开新段），
 * 任何一处合并分支都会改变事件的产生顺序，而前端是按顺序消费事件的。
 */
public class VadSegmenter {

    /**
     * 切分参数，对应上游 {@code SegmenterConfig}。
     *
     * @param threshold              语音概率阈值（{@code prob > threshold} 视为语音帧）
     * @param maxSegmentFrames       语音段最长帧数，超长强制切段（默认 2000 帧 = 60s）
     * @param candidateSilenceFrames 静音多少帧后触发「候选结束」（默认 27 帧 ≈ 800ms）
     * @param confirmFrames          候选后仍静音多少帧 → 确认结束（默认 33 帧 ≈ 1s）
     */
    public record SegmentConfig(float threshold,
                                long maxSegmentFrames,
                                long candidateSilenceFrames,
                                long confirmFrames) {

        /** 上游 {@code SegmenterConfig::default()} 的四个值，逐个对齐，不调参。 */
        public static SegmentConfig defaults() {
            return new SegmentConfig(0.5f, 2000L, 27L, 33L);
        }
    }

    /** 切分事件；带帧号，供调用方换算样本起止做音频裁剪。 */
    public sealed interface SegmentEvent
            permits SpeechStart, SilenceStart, TurnCandidate, TurnSealed {
    }

    /** 检测到语音开始（新一轮语音段起点）。 */
    public record SpeechStart(long frame) implements SegmentEvent {
    }

    /** 语音后首次静音。 */
    public record SilenceStart(long frame) implements SegmentEvent {
    }

    /**
     * 静音达到候选阈值（「可能说完了」）。
     *
     * @param frame          触发帧
     * @param silenceFrames  已连续静音的帧数（<b>含当前帧</b>）
     */
    public record TurnCandidate(long frame, long silenceFrames) implements SegmentEvent {
    }

    /**
     * 确认一轮语音结束。
     *
     * @param startFrame 本轮语音段起始帧
     * @param endFrame   最后一个语音帧（不是静音帧——裁剪音频要用它）
     */
    public record TurnSealed(long startFrame, long endFrame) implements SegmentEvent {
    }

    /**
     * 配置。record 不可变，{@link #setCandidateSilenceFrames(long)} 只能整份替换它
     * （上游是 {@code Copy} 结构体原地改字段，语义上等价）。
     */
    private SegmentConfig cfg;

    /** 内部帧计数（reset 归零，单调递增）。 */
    private long frame;

    /** 当前处于语音段内。 */
    private boolean speechActive;

    /** 本轮语音段起始帧。 */
    private long segmentStart;

    /** 最近一个语音帧。 */
    private long lastSpeechFrame;

    /** 本轮静音起始帧（{@code null} = 尚未静音）。 */
    private Long silenceStartFrame;

    /** 候选已触发标记（防重复触发）。 */
    private boolean candidateFired;

    /** 候选触发帧（确认窗口基准）。 */
    private long candidateFrame;

    public VadSegmenter() {
        this(SegmentConfig.defaults());
    }

    public VadSegmenter(SegmentConfig cfg) {
        this.cfg = cfg == null ? SegmentConfig.defaults() : cfg;
    }

    /** 清空状态（新会话 / 新录音开始时调用）。配置保留——它属于设置，不属于本次会话。 */
    public void reset() {
        this.frame = 0;
        this.speechActive = false;
        this.segmentStart = 0;
        this.lastSpeechFrame = 0;
        this.silenceStartFrame = null;
        this.candidateFired = false;
        this.candidateFrame = 0;
    }

    /** 当前帧号（已处理完的帧数，也就是下一帧的帧号）。 */
    public long currentFrame() {
        return frame;
    }

    /**
     * 设置候选静音帧数。
     *
     * <p>由 {@link EnergyVad#process} 每次调用前按当前设置换算后设置：设置可能在两批
     * 请求之间被用户改过（设置页改完立刻生效，不必重开会话）。
     */
    public void setCandidateSilenceFrames(long frames) {
        this.cfg = new SegmentConfig(cfg.threshold(), cfg.maxSegmentFrames(), frames, cfg.confirmFrames());
    }

    /**
     * 喂一帧语音概率，返回本帧产生的事件（0~2 个）。
     *
     * <p>状态机（30ms/帧）：
     * <ul>
     *   <li>{@code prob > threshold} → 语音帧；首次进入语音 → {@link SpeechStart}；
     *       段长超限 → 强制切段（先 seal 再原地开新段，同一帧出两个事件）</li>
     *   <li>{@code prob <= threshold} 且语音中 → 静音帧；静音起始 → {@link SilenceStart}；
     *       静音 ≥ candidate 帧 → {@link TurnCandidate}；候选后仍静音 ≥ confirm 帧 → {@link TurnSealed}</li>
     *   <li>语音恢复 → 取消候选/确认，回到语音态（短停顿不断句）</li>
     * </ul>
     */
    public List<SegmentEvent> feed(float prob) {
        long frame = this.frame;
        this.frame += 1;
        List<SegmentEvent> events = new ArrayList<>(2);

        if (prob > cfg.threshold()) {
            // ── 语音帧 ──
            this.lastSpeechFrame = frame;
            this.silenceStartFrame = null;
            this.candidateFired = false;
            this.candidateFrame = 0;

            if (!this.speechActive) {
                this.speechActive = true;
                this.segmentStart = frame;
                events.add(new SpeechStart(frame));
            } else if (frame - this.segmentStart >= cfg.maxSegmentFrames()) {
                // 超长段强制切分：先 seal 上一段，再原地开新段。
                // end_frame 取 lastSpeechFrame —— 上面刚把它更新成当前帧，所以这里等于当前帧；
                // 照抄上游（真实语义是「这一段到这里为止，下一帧归新段」）。
                events.add(new TurnSealed(this.segmentStart, this.lastSpeechFrame));
                this.segmentStart = frame;
                events.add(new SpeechStart(frame));
            }
        } else if (this.speechActive) {
            // ── 静音帧（语音后）──
            if (this.silenceStartFrame == null) {
                this.silenceStartFrame = frame;
            }
            long silenceStart = this.silenceStartFrame;
            // 已静音帧数（含当前帧）：第 1 个静音帧 = 静音 1 帧。
            // 上游注释记录过这个坑：早先写成 `frame - silenceStart` 会少算 1 帧
            // （第 N 个静音帧报 N-1），触发点整体推迟 1 帧 —— 设置 800ms 实际 ~830ms 才切段。
            // 这里保留 +1 的正确版本。
            long silenceFrames = frame - silenceStart + 1;

            if (frame == silenceStart) {
                events.add(new SilenceStart(frame));
            }

            if (!this.candidateFired && silenceFrames >= cfg.candidateSilenceFrames()) {
                this.candidateFired = true;
                this.candidateFrame = frame;
                events.add(new TurnCandidate(frame, silenceFrames));
            }

            if (this.candidateFired && frame - this.candidateFrame >= cfg.confirmFrames()) {
                // 确认一轮结束
                this.speechActive = false;
                this.silenceStartFrame = null;
                this.candidateFired = false;
                events.add(new TurnSealed(this.segmentStart, this.lastSpeechFrame));
            }
        }

        return events;
    }
}

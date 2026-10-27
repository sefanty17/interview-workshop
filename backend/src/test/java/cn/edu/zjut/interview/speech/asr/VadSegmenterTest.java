package cn.edu.zjut.interview.speech.asr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VAD 切分状态机（规格 V2 §3.1）。纯逻辑，不起 Spring。
 *
 * <p>断言逐条对应上游 {@code vad_segmenter.rs} 的分支：这些数字（27 / 33 / 2000 帧）
 * 是上游写死的默认值，改任何一个都会让 {@code vad_silence_ms} 的实际触发时刻变化。
 */
class VadSegmenterTest {

    /** 语音概率 0.9 稳定高于默认阈值 0.5；0.1 稳定低于。 */
    private static final float SPEECH = 0.9f;
    private static final float SILENCE = 0.1f;

    @Test
    void 连续语音帧首次触发SpeechStart且只触发一次() {
        VadSegmenter segmenter = new VadSegmenter();

        assertEquals(List.of(new VadSegmenter.SpeechStart(0L)), segmenter.feed(SPEECH));
        assertTrue(segmenter.feed(SPEECH).isEmpty());
        assertTrue(segmenter.feed(SPEECH).isEmpty());
        assertEquals(3L, segmenter.currentFrame());
    }

    @Test
    void 静音到候选帧数触发TurnCandidate() {
        VadSegmenter segmenter = new VadSegmenter();
        segmenter.feed(SPEECH); // frame 0
        segmenter.feed(SPEECH); // frame 1

        List<VadSegmenter.SegmentEvent> events = new ArrayList<>();
        for (int i = 0; i < 27; i++) { // frame 2..28
            events.addAll(segmenter.feed(SILENCE));
        }

        // 首个静音帧出 SilenceStart，之后 27 帧静音的第 27 帧出 TurnCandidate
        assertEquals(new VadSegmenter.SilenceStart(2L), events.get(0));
        VadSegmenter.TurnCandidate candidate = firstOf(events, VadSegmenter.TurnCandidate.class);
        // silence_frames 含当前帧：28 - 2 + 1 = 27。
        // 少算 1 的话这里会是 26，触发点整体推迟 1 帧（上游注释里的那个坑）。
        assertEquals(27L, candidate.silenceFrames());
        assertEquals(28L, candidate.frame());
    }

    @Test
    void 确认帧数后TurnSealed且endFrame是最后语音帧() {
        VadSegmenter segmenter = new VadSegmenter();
        segmenter.feed(SPEECH); // frame 0
        segmenter.feed(SPEECH); // frame 1
        for (int i = 0; i < 27; i++) {
            segmenter.feed(SILENCE); // frame 2..28，候选在 28
        }

        List<VadSegmenter.SegmentEvent> events = new ArrayList<>();
        for (int i = 0; i < 33; i++) { // frame 29..61
            events.addAll(segmenter.feed(SILENCE));
        }

        VadSegmenter.TurnSealed sealed = firstOf(events, VadSegmenter.TurnSealed.class);
        // end_frame 是最后一个语音帧（1），不是静音帧——前端按它裁剪音频尾巴
        assertEquals(0L, sealed.startFrame());
        assertEquals(1L, sealed.endFrame());
    }

    @Test
    void 短停顿被打断候选() {
        VadSegmenter segmenter = new VadSegmenter();
        segmenter.feed(SPEECH); // frame 0
        segmenter.feed(SPEECH); // frame 1

        List<VadSegmenter.SegmentEvent> firstSilence = new ArrayList<>();
        for (int i = 0; i < 10; i++) { // frame 2..11，不到 27 帧
            firstSilence.addAll(segmenter.feed(SILENCE));
        }
        assertTrue(firstSilence.stream().noneMatch(e -> e instanceof VadSegmenter.TurnSealed));
        assertTrue(firstSilence.stream().noneMatch(e -> e instanceof VadSegmenter.TurnCandidate));

        // 语音恢复：短停顿不断句，且不应产生新事件（仍在同一段内）
        assertTrue(segmenter.feed(SPEECH).isEmpty()); // frame 12

        List<VadSegmenter.SegmentEvent> secondSilence = new ArrayList<>();
        for (int i = 0; i < 27; i++) { // frame 13..39
            secondSilence.addAll(segmenter.feed(SILENCE));
        }

        // candidate_fired 被语音恢复清掉，所以候选能重新触发；静音起点也从 13 重新计
        assertEquals(new VadSegmenter.SilenceStart(13L), secondSilence.get(0));
        long candidates = secondSilence.stream()
                .filter(e -> e instanceof VadSegmenter.TurnCandidate).count();
        assertEquals(1L, candidates);
        assertEquals(27L, firstOf(secondSilence, VadSegmenter.TurnCandidate.class).silenceFrames());
    }

    @Test
    void 超长段强制切段() {
        // 上限 10 帧 = 300ms：只为把「超长」压到 10 帧就能观测
        VadSegmenter segmenter = new VadSegmenter(new VadSegmenter.SegmentConfig(0.5f, 10L, 27L, 33L));
        List<VadSegmenter.SegmentEvent> all = new ArrayList<>();
        List<List<VadSegmenter.SegmentEvent>> perFrame = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            List<VadSegmenter.SegmentEvent> events = segmenter.feed(SPEECH);
            perFrame.add(events);
            all.addAll(events);
        }

        // frame 10 时 frame - segmentStart(0) >= 10 → 先 seal 再原地开新段，同帧两个事件
        List<VadSegmenter.SegmentEvent> atFrame10 = perFrame.get(10);
        assertEquals(2, atFrame10.size());
        assertTrue(atFrame10.get(0) instanceof VadSegmenter.TurnSealed);
        assertTrue(atFrame10.get(1) instanceof VadSegmenter.SpeechStart);
        assertEquals(new VadSegmenter.SpeechStart(10L), atFrame10.get(1));
        // 全段只有一次「首帧」SpeechStart（frame 0），没有多余的段起点
        long speechStarts = all.stream()
                .filter(e -> e instanceof VadSegmenter.SpeechStart).count();
        assertEquals(2L, speechStarts);
    }

    @Test
    void setCandidateSilenceFrames改帧数后生效() {
        VadSegmenter segmenter = new VadSegmenter();
        segmenter.setCandidateSilenceFrames(10L);
        segmenter.feed(SPEECH); // frame 0

        List<VadSegmenter.SegmentEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) { // frame 1..10
            events.addAll(segmenter.feed(SILENCE));
        }

        // 默认 27 帧下这里不会有候选；改成 10 帧后第 10 个静音帧就触发
        assertEquals(10L, firstOf(events, VadSegmenter.TurnCandidate.class).silenceFrames());
    }

    @Test
    void reset后帧号归零且状态清空() {
        VadSegmenter segmenter = new VadSegmenter();
        segmenter.feed(SPEECH);
        segmenter.feed(SPEECH); // 处于语音段内
        assertEquals(2L, segmenter.currentFrame());

        segmenter.reset();

        assertEquals(0L, segmenter.currentFrame());
        // 状态清空 → 下一帧重新算「首次进入语音」；没清的话 speech_active 仍是 true，
        // 新一轮第一帧就不会触发 speech_started（上游 asrStartListening 前必调 reset 的原因）
        assertEquals(List.of(new VadSegmenter.SpeechStart(0L)), segmenter.feed(SPEECH));
    }

    private static <T extends VadSegmenter.SegmentEvent> T firstOf(
            List<VadSegmenter.SegmentEvent> events, Class<T> type) {
        for (VadSegmenter.SegmentEvent event : events) {
            if (type.isInstance(event)) {
                return type.cast(event);
            }
        }
        assertFalse(true, "事件列表里没有 " + type.getSimpleName() + "：" + events);
        return null;
    }
}

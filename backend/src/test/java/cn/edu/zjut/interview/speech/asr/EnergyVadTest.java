package cn.edu.zjut.interview.speech.asr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能量门控 VAD（规格 V2 §3.2）。
 *
 * <p>上游这一层是 Silero 模型推理，本项目换成 RMS/SNR 映射（见 {@link EnergyVad} 类注释）。
 * 这里的用例守的是「换算法之后状态机仍然按预期被驱动」：静音不出事件、人声能开段、
 * 停口能在设定时长后封段、噪声底不会把人声吃掉、尾部不足一帧不参与。
 *
 * <p>全部用合成 PCM（正弦 / 全零），不读磁盘音频：样本完全可控，断言才能写死帧号。
 */
class EnergyVadTest {

    private static final int FRAME = EnergyVad.FRAME_SAMPLES;

    @Test
    void 全零输入概率为0且不产生事件() {
        EnergyVad vad = new EnergyVad();

        EnergyVad.VadBatchResult result = vad.process(new float[FRAME * 10], 800);

        assertEquals(10, result.speechProbs().size());
        result.speechProbs().forEach(prob -> assertEquals(0f, prob, 1e-6f));
        assertTrue(result.events().isEmpty());
        assertEquals(10L, result.frame());
    }

    @Test
    void 持续正弦波能触发SpeechStart() {
        EnergyVad vad = new EnergyVad();

        EnergyVad.VadBatchResult result = vad.process(tone(20, 0.3), 800);

        assertEquals(1L, result.events().stream()
                .filter(e -> e instanceof VadSegmenter.SpeechStart).count());
        assertEquals(new VadSegmenter.SpeechStart(0L), result.events().get(0));
        // 振幅 0.3 的正弦 RMS ≈ 0.212，比本底高两个数量级 → 概率被夹到 1
        assertEquals(1f, result.speechProbs().get(19), 1e-6f);
    }

    @Test
    void 语音后接静音能在设定时长后TurnSealed() {
        EnergyVad vad = new EnergyVad();

        // 前 69 帧：10 帧语音 + 59 帧静音。候选在第 36 帧（第 27 个静音帧），
        // 确认要候选后再 33 帧，即第 69 帧（索引）——所以这里还差一帧
        EnergyVad.VadBatchResult beforeSeal = vad.process(concat(tone(10, 0.3), silence(59)), 800);
        assertTrue(beforeSeal.events().stream().noneMatch(e -> e instanceof VadSegmenter.TurnSealed));

        EnergyVad.VadBatchResult sealed = vad.process(silence(1), 800);

        assertEquals(1, sealed.events().size());
        VadSegmenter.TurnSealed turn = (VadSegmenter.TurnSealed) sealed.events().get(0);
        // 封段发生在第 70 帧（索引 69）= 语音 10 帧 + 候选 27 帧（第 36 帧触发）+ 确认 33 帧；
        // frame 是「处理完本批后的帧号」，所以是 70 而不是 69
        assertEquals(70L, sealed.frame());
        assertEquals(0L, turn.startFrame());
        assertEquals(9L, turn.endFrame());
    }

    @Test
    void 噪声底不会把人声吃掉() {
        EnergyVad vad = new EnergyVad();

        // 200 帧 0.001 振幅的底噪（RMS 0.0007，低于绝对门限 → 全部当静音、持续压低噪声底）
        EnergyVad.VadBatchResult noise = vad.process(tone(200, 0.001), 800);
        assertTrue(noise.events().isEmpty());

        // 之后的人声仍必须能开段：噪声底只被非语音帧更新，不会抬到人声水平
        EnergyVad.VadBatchResult speech = vad.process(tone(10, 0.3), 800);
        assertTrue(speech.events().stream().anyMatch(e -> e instanceof VadSegmenter.SpeechStart));
    }

    @Test
    void 不足一帧的尾巴被丢弃() {
        EnergyVad vad = new EnergyVad();

        // 3 整帧 + 100 个残留样本：尾部丢弃而不是补零（补零会被判成静音帧提前封段）
        EnergyVad.VadBatchResult result = vad.process(new float[FRAME * 3 + 100], 800);

        assertEquals(3L, result.frame());
        assertEquals(3, result.speechProbs().size());
    }

    @Test
    void 帧数不足一帧时返回空结果不抛异常() {
        EnergyVad vad = new EnergyVad();

        EnergyVad.VadBatchResult result = assertDoesNotThrow(
                () -> vad.process(new float[FRAME - 12], 800));

        assertEquals(0L, result.frame());
        assertTrue(result.speechProbs().isEmpty());
        assertTrue(result.events().isEmpty());
    }

    /** 440Hz 正弦（16kHz 下每帧 ~14 个周期，RMS 与整段连续正弦一致），避免逐帧相位引入偏差。 */
    private static float[] tone(int frames, double amplitude) {
        float[] pcm = new float[frames * FRAME];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) (amplitude * Math.sin(2 * Math.PI * 440.0 * i / 16_000.0));
        }
        return pcm;
    }

    private static float[] silence(int frames) {
        return new float[frames * FRAME];
    }

    private static float[] concat(float[] a, float[] b) {
        float[] out = new float[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

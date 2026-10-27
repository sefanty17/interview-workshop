package cn.edu.zjut.interview.speech.asr;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAV 编解码（规格 V2 §3.3）。
 *
 * <p>WAV 样本全部由 {@link WavCodec#encode} 自己造，不读磁盘音频：断言的是编解码契约，
 * 不该依赖某个测试音频文件存不存在、用什么采样率录的。
 */
class WavCodecTest {

    @Test
    void encode后decode能还原近似波形() {
        float[] original = new float[1600];
        for (int i = 0; i < original.length; i++) {
            original[i] = (float) (0.5 * Math.sin(2 * Math.PI * 440.0 * i / 16_000.0));
        }

        float[] decoded = WavCodec.decode(WavCodec.encode(original));

        assertEquals(original.length, decoded.length);
        for (int i = 0; i < original.length; i++) {
            // 16bit 量化误差上限就是半个 LSB = 1/32768
            assertTrue(Math.abs(original[i] - decoded[i]) < 1f / 32768f,
                    "第 " + i + " 个样本误差过大：" + original[i] + " → " + decoded[i]);
        }
    }

    @Test
    void decode拒绝非RIFF数据() {
        byte[] notWav = "this is definitely not a wav file".getBytes(StandardCharsets.US_ASCII);

        AsrException e = assertThrows(AsrException.class, () -> WavCodec.decode(notWav));

        assertEquals(AsrErrorKind.INVALID_AUDIO, e.kind());
        assertEquals(400, e.status().value());
    }

    @Test
    void decode拒绝44100采样率() {
        // 44100 是最常见的不合规值：浏览器直接录、或 ffmpeg 未重采样
        byte[] wav = wav(44_100, 1, 16, null, new short[]{100, 200, 300});

        AsrException e = assertThrows(AsrException.class, () -> WavCodec.decode(wav));

        assertEquals(AsrErrorKind.INVALID_AUDIO, e.kind());
        // message 必须带实际值：前端据此判断「要不要重采样」，只说「格式不对」等于没说
        assertTrue(e.getMessage().contains("44100"), "message 里没有实际采样率：" + e.getMessage());
    }

    @Test
    void decode能跳过未知chunk() {
        // 浏览器 OfflineAudioContext 导出、ffmpeg 转出的 WAV 常在 fmt 与 data 之间插 LIST/fact。
        // 按「data 在偏移 36」写死会直接解错，所以这里专门造这种文件。
        byte[] listPayload = "INFOsome metadata".getBytes(StandardCharsets.US_ASCII);
        byte[] listChunk = chunk("LIST", listPayload);
        byte[] wav = wav(16_000, 1, 16, listChunk, new short[]{1000, -1000, 2000, -2000});

        float[] pcm = WavCodec.decode(wav);

        assertEquals(4, pcm.length);
        assertEquals(1000 / 32768f, pcm[0], 1e-7f);
        assertEquals(-2000 / 32768f, pcm[3], 1e-7f);
    }

    @Test
    void decode拒绝8bit() {
        byte[] wav = wav(16_000, 1, 8, null, new short[]{100, 200});

        AsrException e = assertThrows(AsrException.class, () -> WavCodec.decode(wav));

        assertEquals(AsrErrorKind.INVALID_AUDIO, e.kind());
        assertTrue(e.getMessage().contains("8"), "message 里没有实际位深：" + e.getMessage());
    }

    /**
     * 造 WAV：44 字节标准头 + 可选额外 chunk + data。
     *
     * @param extraChunk 已拼好的整块（id + size + payload + 对齐），{@code null} = 不插
     */
    private static byte[] wav(int sampleRate, int channels, int bits, byte[] extraChunk, short[] samples) {
        int dataSize = samples.length * 2;
        int extraLen = extraChunk == null ? 0 : extraChunk.length;
        ByteBuffer buffer = ByteBuffer.allocate(12 + 24 + extraLen + 8 + dataSize)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(ascii("RIFF"));
        buffer.putInt(4 + 24 + extraLen + 8 + dataSize);
        buffer.put(ascii("WAVE"));
        buffer.put(ascii("fmt "));
        buffer.putInt(16);
        buffer.putShort((short) 1); // PCM
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * bits / 8);
        buffer.putShort((short) (channels * bits / 8));
        buffer.putShort((short) bits);
        if (extraChunk != null) {
            buffer.put(extraChunk);
        }
        buffer.put(ascii("data"));
        buffer.putInt(dataSize);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    /** 拼一个完整的 chunk（payload 奇数长度补 1 字节 0）。 */
    private static byte[] chunk(String id, byte[] payload) {
        int pad = payload.length % 2 == 1 ? 1 : 0;
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length + pad).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(ascii(id));
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}

package cn.edu.zjut.interview.speech.asr;

import org.springframework.http.HttpStatus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * WAV 编解码：把 WAV 字节解成 16kHz / 单声道 / 16bit 的 float 采样（[-1,1]）。
 *
 * <p>为什么后端要解 WAV：{@code /vad} 收的是 PCM 帧数组，{@code /recognize} 收的是整段
 * WAV 字节，而 V3 前端拿到的就是同一份录音——后端得能把 WAV 拆成帧喂 VAD、也能校验
 * 「这段音频到底能不能送 provider」。校验做在入口而不是 provider 里：provider 拿到
 * 44100Hz 的音频不会报错，它只会返回一段识别不出的文本，用户看到的是「识别总是空的」。
 *
 * <p>只支持这一种格式，不做重采样：上游前端在 {@code OfflineAudioContext} 里已经
 * 转成 16k/单声道，V3 会照做。这里遇到别的格式就明确拒绝，并把实际值写进 message
 * ——前端拿到「采样率 44100 不支持，需要 16000」才知道该重采样，而不是猜。
 */
public final class WavCodec {

    /** 期望采样率，与 {@link EnergyVad} 的帧长约定绑定（512 samples @16k）。 */
    public static final int SAMPLE_RATE = 16_000;
    /** 期望声道数。多声道会让帧内混两路人的声音，VAD 的 RMS 与识别都会失真。 */
    public static final int CHANNELS = 1;
    /** 期望位深。8bit 是无符号的，解码方式与 16bit 完全不同，混在一起容易出错。 */
    public static final int BITS_PER_SAMPLE = 16;

    /** PCM 格式码（WAVE_FORMAT_PCM）。3 = IEEE float，6 = A-law，都不是我们要的。 */
    private static final int WAVE_FORMAT_PCM = 1;
    /** 标准 WAV 头长度：RIFF(12) + fmt(24) + data 头(8)。 */
    private static final int HEADER_BYTES = 44;

    private WavCodec() {
    }

    /**
     * 解码。
     *
     * <p>按 chunk 遍历而不是写死偏移：浏览器 {@code OfflineAudioContext} 导出、ffmpeg 转出的
     * WAV 常在 {@code fmt } 与 {@code data} 之间插 {@code LIST} / {@code fact} chunk，
     * 按「fmt 在 12、data 在 36」的固定偏移去读会直接把 {@code LIST} 的元数据当成采样。
     *
     * @return float 采样，取值范围 [-1,1)
     * @throws AsrException {@link AsrErrorKind#INVALID_AUDIO} / 400，message 里带实际格式值
     */
    public static float[] decode(byte[] wav) {
        if (wav == null || wav.length == 0) {
            throw invalid("WAV 数据为空");
        }
        if (wav.length < 12
                || !matches(wav, 0, "RIFF")
                || !matches(wav, 8, "WAVE")) {
            throw invalid("不是 WAV 文件（缺少 RIFF/WAVE 头）");
        }

        int fmtOffset = -1;
        int fmtSize = 0;
        int dataOffset = -1;
        int dataSize = 0;

        // chunk 从偏移 12 开始：每块 = id(4) + size(4) + payload(size)，payload 奇数长度要补 1 字节对齐
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = new String(wav, offset, 4, StandardCharsets.US_ASCII);
            int size = readInt(wav, offset + 4);
            if (size < 0) {
                // 负数说明不是合法 size（或文件被截断成乱码），继续读只会越界
                throw invalid("WAV chunk 长度非法：" + size);
            }
            int payload = offset + 8;
            if ("fmt ".equals(id)) {
                if (size < 16 || payload + size > wav.length) {
                    throw invalid("WAV 的 fmt chunk 损坏（长度 " + size + "）");
                }
                fmtOffset = payload;
                fmtSize = size;
            } else if ("data".equals(id)) {
                dataOffset = payload;
                // 有的编码器把 data size 写成 0 或整个文件长度（流式写入），按实际剩余字节兜底
                dataSize = Math.min(size, wav.length - payload);
                break;
            }
            offset = payload + size + (size % 2 == 1 ? 1 : 0);
        }

        if (fmtOffset < 0) {
            throw invalid("WAV 缺少 fmt chunk");
        }
        if (dataOffset < 0) {
            throw invalid("WAV 缺少 data chunk");
        }
        if (fmtSize < 16) {
            throw invalid("WAV 的 fmt chunk 太短（" + fmtSize + " 字节，至少 16）");
        }

        int audioFormat = readShort(wav, fmtOffset);
        int channels = readShort(wav, fmtOffset + 2);
        int sampleRate = readInt(wav, fmtOffset + 4);
        int bitsPerSample = readShort(wav, fmtOffset + 14);

        if (audioFormat != WAVE_FORMAT_PCM) {
            throw invalid("音频编码格式 " + audioFormat + " 不是 PCM（需要 " + WAVE_FORMAT_PCM + "）");
        }
        if (channels != CHANNELS) {
            throw invalid("声道数 " + channels + " 不支持，需要单声道");
        }
        if (sampleRate != SAMPLE_RATE) {
            throw invalid("采样率 " + sampleRate + " 不支持，需要 " + SAMPLE_RATE);
        }
        if (bitsPerSample != BITS_PER_SAMPLE) {
            throw invalid("位深 " + bitsPerSample + " 不支持，需要 " + BITS_PER_SAMPLE + "bit");
        }

        int samples = dataSize / 2;
        float[] pcm = new float[samples];
        for (int i = 0; i < samples; i++) {
            // 采样是<strong>有符号</strong> 16bit（readShort 按无符号读，这里转回 short），
            // 除以 32768 而不是 32767：short 的负半轴比正半轴多一个值（-32768），
            // 用 32768 才能让两侧都落在 [-1,1) 内且不越界。
            short sample = (short) readShort(wav, dataOffset + i * 2);
            pcm[i] = sample / 32768f;
        }
        return pcm;
    }

    /**
     * 编码成 44 字节标准头的 WAV（16kHz / 单声道 / 16bit）。
     *
     * <p>用途：测试造样本、V3 的桩。生产链路里 WAV 永远由前端生成，后端不解码回 WAV。
     */
    public static byte[] encode(float[] pcm) {
        float[] samples = pcm == null ? new float[0] : pcm;
        int dataSize = samples.length * 2;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);                // fmt 块大小
        buffer.putShort((short) WAVE_FORMAT_PCM);
        buffer.putShort((short) CHANNELS);
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8); // 字节率
        buffer.putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8));   // 块对齐
        buffer.putShort((short) BITS_PER_SAMPLE);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);
        for (float sample : samples) {
            float clamped = Math.max(-1f, Math.min(1f, sample));
            // 乘以 32768 后四舍五入；1.0 会溢出成正 32768 → 夹到 32767
            int value = Math.round(clamped * 32768f);
            buffer.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
        }
        return buffer.array();
    }

    private static boolean matches(byte[] data, int offset, String ascii) {
        byte[] expected = ascii.getBytes(StandardCharsets.US_ASCII);
        if (offset + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** WAV 全小端。 */
    private static int readInt(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            throw invalid("WAV 数据被截断（偏移 " + offset + " 处读不到 4 字节）");
        }
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /** 无符号 16 位（fmt 里的字段都是无符号；位深 16 也不该被读成负数）。 */
    private static int readShort(byte[] data, int offset) {
        if (offset + 2 > data.length) {
            throw invalid("WAV 数据被截断（偏移 " + offset + " 处读不到 2 字节）");
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static AsrException invalid(String message) {
        return new AsrException(AsrErrorKind.INVALID_AUDIO, HttpStatus.BAD_REQUEST, message);
    }
}

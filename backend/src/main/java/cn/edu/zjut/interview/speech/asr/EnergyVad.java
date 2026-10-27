package cn.edu.zjut.interview.speech.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 能量门控 VAD：用每帧 RMS 与自适应噪声底的信噪比，映射出 0~1 的「语音概率」，
 * 再喂 {@link VadSegmenter} 出事件。
 *
 * <p><b>这是本项目新写的算法，上游没有</b>（上游跑 {@code silero-vad.onnx}，
 * 见 {@code asr/vad.rs}）。原因：本项目不加 Maven 依赖 ⇒ 没有 ONNX Runtime ⇒
 * 模型推理做不到；而 VAD 的「prob → 事件」逻辑全在状态机里，与推理无关，
 * 所以只要有人愿意给 prob，状态机就能照常工作。能量门控填的就是这个位置。
 *
 * <p>能力边界要说清楚：能量门控分得清「有人说话」与「环境静音」，但分不清语音与
 * 非语音的噪声（关门声、键盘声都会被当成语音）。它的作用是<b>断句</b>，
 * 不是语音识别——识别由 provider 负责，噪声段送过去也只是识别不出内容。
 *
 * <p>状态是<strong>有状态服务</strong>：一批帧会改变帧号、静音计时与噪声底，
 * 所以方法级同步；并发两批请求交错会让帧号与事件顺序错乱。
 */
@Component
public class EnergyVad {

    private static final Logger log = LoggerFactory.getLogger(EnergyVad.class);

    /** 单帧样本数。上游 512 samples @16k，与 vad.rs 一致。 */
    public static final int FRAME_SAMPLES = 512;

    /**
     * 一帧折算的毫秒数。上游全程按 30ms 换算（512/16000 实际是 32ms），
     * 照抄 30：否则同一个 {@code vad_silence_ms} 在两边换算出的帧数不同，
     * 触发时刻会差 6%（800ms 设置上游 27 帧、改 32ms 就变 25 帧，切段提前 64ms）。
     */
    public static final int FRAME_MS = 30;

    /**
     * 绝对静音门限（RMS）。
     *
     * <p>为什么是 0.004：16bit 满量程 1.0，0.004 约等于 -48dBFS，是「安静的房间里
     * 几乎听不到任何东西」的量级。低于它的帧直接判 0 概率、不参与 SNR 计算——
     * 否则数字静音（麦克风自噪）算出的 SNR 会在 1 附近抖动，被 LOW_SNR 放大成假语音。
     */
    private static final double ABSOLUTE_FLOOR = 0.004;

    /**
     * SNR 下限：比本底响 2 倍（+6dB）才开始有「可能是语音」的分量。
     *
     * <p>取 2 而不是 1：麦克风自噪的即时 RMS 相对本底本来就有 ±1.5 倍的自然波动，
     * 从 1 开始映射会让静音帧的概率在 0 附近随机跳动。
     */
    private static final double LOW_SNR = 2.0;

    /**
     * SNR 上限：比本底响 6 倍（+15.6dB）即认为确定是语音。
     *
     * <p>{@code LOW_SNR → HIGH_SNR} 之间线性过渡是这套算法能用的关键：
     * 只用一个硬阈值（比如 SNR > 3）时，说话人音量刚好在临界值附近会逐帧来回跨越，
     * 状态机就会收到一串 {@code 1 0 1 0} 的概率，产生一串只持续 1 帧的假
     * {@code speech_started}，前端表现为「录音被反复打断」。线性过渡给了 2→6 倍的
     * 缓冲区，概率单调上升而不是跳变。
     */
    private static final double HIGH_SNR = 6.0;

    /**
     * 噪声底初值 0.002（约 -54dBFS）：比典型室内本底略高，宁可前一秒偏「聋」，
     * 也不要一上电就把空调声当语音触发录音。前几十帧之后它会收敛到真实本底。
     */
    private static final double NOISE_FLOOR_INIT = 0.002;

    /**
     * 噪声底平滑系数 0.005（等效时间常数 200 帧 ≈ 6 秒）。
     *
     * <p>为什么这么慢：噪声底要跟得上「房间从安静变嘈杂」，但不能被一两帧的突发
     * 声响带跑。6 秒的时间常数意味着一次 100ms 的咳嗽只把本底抬高 1%，
     * 而持续的环境噪声会在几秒内被正确吸收。
     */
    private static final double NOISE_ALPHA = 0.005;

    /** 噪声底下限 1e-4：防止本底收敛到 0 后做除数（{@code rms / 0} = Infinity）。 */
    private static final double NOISE_FLOOR_MIN = 1e-4;

    /** 概率下限；低于它按「不是语音帧」处理，用该帧 RMS 更新噪声底。 */
    private static final double SPEECH_PROB_FLOOR = 0.5;

    /** 语音切分状态机（与概率来源解耦，见 {@link VadSegmenter}）。 */
    private final VadSegmenter segmenter = new VadSegmenter();

    /** 自适应噪声底（RMS 量级）。 */
    private double noiseFloor = NOISE_FLOOR_INIT;

    /**
     * 逐帧处理一批 PCM，返回本批的语音概率（每帧一个）与状态机事件。
     *
     * <p>为什么不带调试开关：{@link #process(float[], int, boolean)} 是带开关的版本，
     * 这个两参重载给「不需要日志的调用方」（含单测）用，避免每处都传 {@code false}。
     */
    public VadBatchResult process(float[] pcm, int silenceMs) {
        return process(pcm, silenceMs, false);
    }

    /**
     * 逐帧处理一批 PCM。
     *
     * @param pcm       任意长度；{@code pcm.length % 512 != 0} 时尾部不足一帧的样本<b>丢弃</b>
     *                  （不补零：补零会被判成静音帧，提前触发 {@code turn_sealed}）
     * @param silenceMs 当前设置的静音计时（毫秒），每批都重新换算——设置可能刚被改过
     * @param debugLog  是否输出逐批调试日志（上游「详细VAD能量检测日志开关」）
     */
    public synchronized VadBatchResult process(float[] pcm, int silenceMs, boolean debugLog) {
        // 上游 vad.rs::set_silence_timeout_ms 用 div_ceil(ms, 30)；Java 没有整数 div_ceil，
        // 用 ceil(ms / 30.0) 等价（800 → 27，与上游一致）。
        long frames = (long) Math.ceil(silenceMs / (double) FRAME_MS);
        segmenter.setCandidateSilenceFrames(frames);

        int samples = pcm == null ? 0 : pcm.length;
        int fullFrames = samples / FRAME_SAMPLES;
        if (samples % FRAME_SAMPLES != 0) {
            // 前端每 ~512ms 批量上传一次，块边界与帧边界不保证对齐；丢尾部而不是补零，
            // 见 process 的 pcm 参数说明。debug 级别：正常运行时每批都会命中，不该刷 info。
            log.debug("[ASR/VAD] 丢弃尾部 {} 个不足一帧的样本（本批 {} 样本）",
                    samples % FRAME_SAMPLES, samples);
        }

        List<Float> probs = new ArrayList<>(fullFrames);
        List<VadSegmenter.SegmentEvent> events = new ArrayList<>();
        for (int i = 0; i < fullFrames; i++) {
            float prob = frameProb(pcm, i * FRAME_SAMPLES);
            probs.add(prob);
            events.addAll(segmenter.feed(prob));
        }

        long frame = segmenter.currentFrame();
        if (debugLog && fullFrames > 0) {
            // 一行一批（不是一行一帧）：前端每 ~512ms 调一次，逐帧打印会有 16 倍噪音。
            // SLF4J 不支持 "%.3f" 占位符，先把均值格式化成字符串。
            double avg = probs.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
            String types = events.stream().map(EnergyVad::eventName)
                    .reduce((a, b) -> a + "," + b).orElse("-");
            log.info("[ASR/VAD] frame={} frames={} prob={} events={}",
                    frame, fullFrames, String.format("%.3f", avg), types);
        }
        return new VadBatchResult(probs, events, frame);
    }

    /** 新会话开始 / 录音重开时调用：清状态机 + 清噪声底。 */
    public synchronized void reset() {
        segmenter.reset();
        noiseFloor = NOISE_FLOOR_INIT;
    }

    /**
     * 算一帧的语音概率。
     *
     * @param pcm    整批样本
     * @param offset 本帧起点
     */
    private float frameProb(float[] pcm, int offset) {
        double sum = 0.0;
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            double x = pcm[offset + i];
            sum += x * x;
        }
        double rms = Math.sqrt(sum / FRAME_SAMPLES);

        if (rms < ABSOLUTE_FLOOR) {
            // 绝对静音：直接 0。这一支也更新噪声底——安静段的 RMS 正是本底的最好样本。
            updateNoiseFloor(rms);
            return 0.0f;
        }

        double snr = rms / Math.max(noiseFloor, NOISE_FLOOR_MIN);
        double prob = (snr - LOW_SNR) / (HIGH_SNR - LOW_SNR);
        prob = Math.max(0.0, Math.min(1.0, prob));

        if (prob < SPEECH_PROB_FLOOR) {
            // 噪声底只在「非语音帧」更新。为什么：语音帧也拿去更新，本底会被抬到人声
            // 水平，之后 SNR 永远在 1 附近 → 再也检测不到语音（自激）。
            updateNoiseFloor(rms);
        }
        return (float) prob;
    }

    /** 一阶低通：{@code floor = (1-α)·floor + α·rms}。 */
    private void updateNoiseFloor(double rms) {
        noiseFloor = (1.0 - NOISE_ALPHA) * noiseFloor + NOISE_ALPHA * rms;
    }

    /**
     * 事件名，只给调试日志用（HTTP 契约的 snake_case 在 {@link VadEventView#from}）。
     *
     * <p>用 instanceof 而不是 switch 模式匹配：项目编译级别是 Java 17，
     * 模式匹配 switch 要到 21 才转正。
     */
    private static String eventName(VadSegmenter.SegmentEvent event) {
        if (event instanceof VadSegmenter.SpeechStart) {
            return "speech_started";
        }
        if (event instanceof VadSegmenter.SilenceStart) {
            return "silence_started";
        }
        if (event instanceof VadSegmenter.TurnCandidate) {
            return "turn_candidate";
        }
        return "turn_sealed";
    }

    /**
     * 一批的处理结果。
     *
     * @param speechProbs 本批每帧的概率（顺序与帧序一致；空批为空列表）
     * @param events      本批发生的全部事件（一批里可能同时出现 silence_started + turn_candidate）
     * @param frame       处理完本批后的当前帧号
     */
    public record VadBatchResult(List<Float> speechProbs,
                                 List<VadSegmenter.SegmentEvent> events,
                                 long frame) {
    }
}

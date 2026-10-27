package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;

import java.util.Locale;
import java.util.Map;

/**
 * Style-Bert-VITS2 适配器，对应上游 {@code adapters/sbv2.rs}。
 *
 * <p>协议要点（全部核对自 {@code sbv2.rs:48-79}）：<b>POST + 查询参数、没有请求体</b>，
 * 端点 {@code /voice}，音频格式只体现在 {@code Accept} 头上。
 *
 * <p>与上游的三处差异，都是因为「前端多给了字段」：
 * <ul>
 *   <li>{@code speed}：上游把 {@code length} <b>硬编码成 "1.0"</b>，根本不读速度
 *       （{@code sbv2.rs:66}）。但前端 sbv2 有一档「语速 0.5–2×」滑杆，照抄上游等于让这个
 *       控件彻底失效，所以这里按 {@code length = 1/speed} 发送——{@code length} 是
 *       SBV2 的时长倍率，与速度互为倒数；speed=1（默认值）时结果与上游字面量
 *       {@code "1.0"} 完全一致，因此默认行为没有任何改变；</li>
 *   <li>{@code styleId}：上游用风格<b>名称</b>（{@code style=Neutral}）而不是 id，
 *       两者无法互相换算，本批忽略该字段（用 {@code style=Neutral} 兜底）；</li>
 *   <li>{@code audioFormat}：前端 sbv2 没有这个字段，取全局 {@code ttsAudioFormat}
 *       （默认 wav），与上游「provider 级 audio_format」的位置一致。</li>
 * </ul>
 */
public class Sbv2Adapter implements TtsAdapter {

    public static final String TYPE = "sbv2";

    /** 上游 sbv2.rs 里的固定参数（该服务本地部署，这些值就是它的默认推理参数）。 */
    private static final String DEFAULT_STYLE = "Neutral";
    private static final String DEFAULT_STYLE_WEIGHT = "1.0";
    private static final String DEFAULT_SDP_RATIO = "0.2";
    private static final String DEFAULT_NOISE = "0.6";
    private static final String DEFAULT_NOISEW = "0.8";
    private static final String DEFAULT_SPLIT_INTERVAL = "0.5";

    private final TtsSettings settings;

    public Sbv2Adapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);
        // port 传 0：SBV2 的地址是一个整体（前端就一个 apiUrl 输入框），这里只借它做规整
        String apiUrl = TtsParams.urlWithPort(TtsParams.str(cfg, "apiUrl", ""), 0);
        if (apiUrl.isEmpty()) {
            throw TtsException.notConfigured("sbv2 配置缺少 apiUrl（Style-Bert-VITS2 服务地址）");
        }

        double speed = TtsParams.dbl(cfg, "speed", 1.0);
        // speed <= 0 只可能来自手改的请求体，会让 length 变成 Infinity/负数，退回 1.0
        String length = TtsParams.num(speed > 0 ? 1.0 / speed : 1.0);

        Map<String, String> query = TtsParams.query(
                "encoding", "utf-8",
                "model_name", TtsParams.str(cfg, "modelName", ""),
                "model_id", "0",
                "speaker_id", String.valueOf(TtsParams.intVal(cfg, "speakerId", 0)),
                "sdp_ratio", DEFAULT_SDP_RATIO,
                "noise", DEFAULT_NOISE,
                "noisew", DEFAULT_NOISEW,
                "length", length,
                "language", language(TtsParams.str(cfg, "language", "zh")),
                "split_interval", DEFAULT_SPLIT_INTERVAL,
                "style", DEFAULT_STYLE,
                "style_weight", DEFAULT_STYLE_WEIGHT,
                "text", text);

        return TtsHttp.post(apiUrl + "/voice", query,
                Map.of("Accept", accept(settings.getTtsAudioFormat())),
                null, settings.getTtsTimeout());
    }

    /**
     * 语言码映射，逐字照上游 {@code sbv2.rs:29-34}：SBV2 只认两字母区的 {@code JP/ZH/EN}。
     *
     * <p>其它值原样透传（上游就是 {@code other => other}）：前端下拉里有 {@code zh-CN}、
     * {@code ko} 等，SBV2 服务端不认识会自己报错——静默改成 zh 反而让用户以为「选了韩语在念中文」。
     */
    private static String language(String lang) {
        return switch (lang.toLowerCase(Locale.ROOT)) {
            case "ja" -> "JP";
            case "zh" -> "ZH";
            case "en" -> "EN";
            default -> lang;
        };
    }

    /** 音频格式 → MIME，照 {@code sbv2.rs:49-56}。未知格式按 wav，与该适配器上游默认分支一致。 */
    static String accept(String audioFormat) {
        return switch (audioFormat == null ? "" : audioFormat.toLowerCase(Locale.ROOT)) {
            case "flac" -> "audio/flac";
            case "mp3" -> "audio/mpeg";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            default -> "audio/wav";
        };
    }
}

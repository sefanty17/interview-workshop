package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Simple-Vits-API 的 Bert-VITS2 适配器，对应上游 {@code adapters/bv2.rs}。
 *
 * <p>协议：POST {@code /voice/bert-vits2}，JSON 包体。键名照 {@code bv2.rs:35-45}：
 * {@code id / format / lang / length / noise / noisew / segment_size / sdp_radio / text}。
 *
 * <p>与上游的三处差异：
 * <ul>
 *   <li><b>{@code sdp_radio} 改成 {@code sdp_ratio}</b>：上游那个键名是拼写错误
 *       （{@code bv2.rs:43}，Bert-VITS2 与 Simple-Vits-API 的接口参数叫 {@code sdp_ratio}，
 *       同族适配器 sbv2/sbv2api 也都写 {@code sdp_ratio}）。沿用错名等于让前端的
 *       「SDP 噪声比」滑杆永远不生效，所以这里发正确键名；</li>
 *   <li>{@code styleId} 不上送：上游该请求体里没有任何风格字段，本批忽略；</li>
 *   <li>{@code format} 取全局 {@code ttsAudioFormat}（默认 wav）：前端 bv2 的字段表里
 *       没有音频格式，上游也是 provider 级参数。</li>
 * </ul>
 */
public class Bv2Adapter implements TtsAdapter {

    public static final String TYPE = "bv2";

    /** 上游 bv2.rs:39-42 的固定推理参数（该服务只暴露这几个旋钮，前端也只给了 sdpRatio）。 */
    private static final double DEFAULT_LENGTH = 1.0;
    private static final double DEFAULT_NOISE = 0.33;
    private static final double DEFAULT_NOISEW = 0.4;
    private static final int DEFAULT_SEGMENT_SIZE = 50;
    private static final double DEFAULT_SDP_RATIO = 0.2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TtsSettings settings;

    public Bv2Adapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);
        String apiUrl = TtsParams.urlWithPort(TtsParams.str(cfg, "apiUrl", ""), 0);
        if (apiUrl.isEmpty()) {
            throw TtsException.notConfigured("bv2 配置缺少 apiUrl（Bert-VITS2 服务地址）");
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("id", TtsParams.intVal(cfg, "speakerId", 0));
        body.put("format", audioFormat(settings.getTtsAudioFormat()));
        // lang 原样透传（上游 bv2.rs 不做 ja→JP 之类的映射，Simple-Vits-API 认的是 zh/ja/en）
        body.put("lang", TtsParams.str(cfg, "language", "zh"));
        body.put("length", DEFAULT_LENGTH);
        body.put("noise", DEFAULT_NOISE);
        body.put("noisew", DEFAULT_NOISEW);
        body.put("segment_size", DEFAULT_SEGMENT_SIZE);
        body.put("sdp_ratio", TtsParams.dbl(cfg, "sdpRatio", DEFAULT_SDP_RATIO));
        body.put("text", text);

        return TtsHttp.post(apiUrl + "/voice/bert-vits2", null, null, body.toString(), settings.getTtsTimeout());
    }

    /** 只认 Simple-Vits-API 支持的四种；其它（空、手填错）退回 wav，跟上游默认一致。 */
    static String audioFormat(String value) {
        String format = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (format) {
            case "mp3", "flac", "ogg" -> format;
            default -> "wav";
        };
    }
}

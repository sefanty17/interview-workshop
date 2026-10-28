package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AIVIS Cloud API 适配器，对应上游 {@code adapters/aivis.rs}。
 *
 * <p>协议：{@code POST {apiUrl}/tts/synthesize}，JSON 包体，{@code Accept} 头按
 * {@code output_format} 变，{@code Authorization: Bearer {apiKey}}，响应体就是音频字节。
 *
 * <p>与上游的差异（每一处都是本项目自己的配置来源决定的，不是抄漏）：
 * <ul>
 *   <li><b>{@code speaker_uuid} 是本项目扩展</b>：上游 {@code voice_maker.rs:425} 恒传
 *       {@code None}，因为上游压根没有「说话人 ID」这个配置项；本项目前端 aivis 表单
 *       有「说话人 ID」输入框（{@code SettingsTts.vue} 第 883-890 行），不读它就是一个
 *       永远填不进去的死字段。所以 {@code speakerId > 0} 时把它作为
 *       {@code speaker_uuid} 送出去，为 0 时与上游一致（不送）；</li>
 *   <li><b>{@code intonation_scale} 是本项目扩展</b>：AIVIS API 的标准字段，上游未送
 *       （固定走服务端默认），同样是前端有「语调」滑杆而上游没有对应配置；</li>
 *   <li><b>{@code speaking_rate} 取配置</b>：上游 {@code aivis.rs:75} 写死 {@code 1.0}，
 *       本项目读前端「语速」滑杆，没配才落回 1.0；</li>
 *   <li><b>{@code language} 固定 {@code "ja"}</b>：沿用上游 {@code voice_maker.rs:426}
 *       的固定值（Aivis 是日语语音服务），前端 aivis 表单未提供语种配置，没有其他取值来源；</li>
 *   <li><b>{@code style_id} / {@code style_name} 不发</b>：上游结构体里有这两个字段，
 *       但 {@code AivisAdapter::new} 从来不赋值（恒 {@code None}），请求里也就不出现。</li>
 * </ul>
 */
public class AivisAdapter implements TtsAdapter {

    public static final String TYPE = "aivis";

    /** 上游 {@code voice_maker.rs:423} 的默认地址，也是 {@code config/tts.rs:111-116} 的默认值。 */
    private static final String DEFAULT_API_URL = "https://api.aivis-project.com/v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TtsSettings settings;

    public AivisAdapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);

        String apiUrl = stripTrailingSlashes(TtsParams.str(cfg, "apiUrl", DEFAULT_API_URL));
        String apiKey = TtsParams.str(cfg, "apiKey", "");
        if (apiKey.isEmpty()) {
            // 与上游 aivis.rs:34-37 同一处拦截：云 API 没有密钥必然 401，本地先报能指名缺哪个字段
            throw TtsException.notConfigured("Aivis 配置缺少 apiKey（AIVIS 云 API 密钥）");
        }
        // modelUuid 不拦：上游 voice_maker.rs:422 是 unwrap_or_default，空值由服务端报 400。
        // 这里照抄上游——本地拦了反而会挡住「AIVIS 服务端接受空 model_uuid 走默认模型」的情况
        String modelUuid = TtsParams.str(cfg, "modelUuid", "");

        int speakerId = TtsParams.intVal(cfg, "speakerId", 0);
        double speed = TtsParams.dbl(cfg, "speed", 1.0);
        double intonation = TtsParams.dbl(cfg, "intonation", 1.0);
        String audioFormat = audioFormat(settings.getTtsAudioFormat());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model_uuid", modelUuid);
        if (speakerId > 0) {
            body.put("speaker_uuid", String.valueOf(speakerId));
        }
        body.put("language", "ja");
        body.put("use_ssml", true);
        body.put("speaking_rate", speed);
        body.put("emotional_intensity", 1.0);
        body.put("tempo_dynamics", 1.0);
        body.put("pitch", 0.0);
        body.put("volume", 1.0);
        body.put("intonation_scale", intonation);
        body.put("leading_silence_seconds", 0.1);
        body.put("trailing_silence_seconds", 0.1);
        body.put("line_break_silence_seconds", 0.4);
        body.put("output_format", audioFormat);
        body.put("output_sampling_rate", 44100);
        body.put("output_audio_channels", "mono");
        body.put("text", text);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", accept(audioFormat));
        headers.put("Authorization", "Bearer " + apiKey);

        // 响应字节原样返回，不校验 RIFF：output_format 可能是 mp3/flac，校验只会误杀
        return TtsHttp.post(apiUrl + "/tts/synthesize", null, headers, toJson(body), settings.getTtsTimeout());
    }

    /** 上游 {@code aivis.rs:53-60} 的 Accept 映射，逐字照抄；未列出的格式一律回 audio/mpeg。 */
    static String accept(String audioFormat) {
        return switch (audioFormat) {
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "mp3" -> "audio/mpeg";
            case "aac" -> "audio/aac";
            case "opus" -> "audio/ogg; codecs=opus";
            default -> "audio/mpeg";
        };
    }

    /** 全局 {@code ttsAudioFormat}；空值回落 wav（与上游 {@code audio_format} 默认一致）。 */
    static String audioFormat(String configured) {
        String format = configured == null ? "" : configured.trim();
        return format.isEmpty() ? "wav" : format;
    }

    private static String stripTrailingSlashes(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // 包体里只有字符串与数字，序列化失败只可能是代码写错；报出来比吞掉好查
            throw TtsException.requestFailed("Aivis 请求体序列化失败：" + e.getMessage());
        }
    }
}

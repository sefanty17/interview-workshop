package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

/**
 * 阿里云百炼 CosyVoice 适配器，对应上游 {@code adapters/cosyvoice.rs}。
 *
 * <p>协议见 {@code cosyvoice.rs:1-6}：{@code POST {base}/services/audio/tts/SpeechSynthesizer}，
 * 响应是 <b>JSON 而不是音频</b>——里面给 {@code output.audio.url}，还要再 GET 一次下载。
 *
 * <p>与上游的两处差异：
 * <ul>
 *   <li><b>方言用前端枚举拼 instruction</b>：上游 {@code instruction} 是调用方给的自由文本
 *       （{@code cosyvoice.rs:46-53} 的 {@code with_instruction}），前端 cosyvoice 表单里
 *       只有一个 {@code dialect} 下拉（广东话/东北话/河南话/四川话/上海话），
 *       所以这里把枚举值拼成「用四川话说话。」；</li>
 *   <li><b>非 2xx 不二次解析 code/message</b>：上游会从错误体里取 {@code code}/{@code message}
 *       重拼一条消息，而 {@link TtsHttp} 抛错时已经带上响应体前 200 字（就是那段含
 *       {@code code}/{@code message} 的 JSON），再解析一遍只是同一信息换个措辞。</li>
 * </ul>
 */
public class CosyvoiceAdapter implements TtsAdapter {

    public static final String TYPE = "cosyvoice";

    private static final String DEFAULT_API_URL = "https://dashscope.aliyuncs.com/api/v1";
    private static final String SYNTHESIS_PATH = "/services/audio/tts/SpeechSynthesizer";
    private static final String DEFAULT_MODEL = "cosyvoice-v3.5-flash";

    /** 上游 {@code cosyvoice.rs:72-75} 的固定音频参数。 */
    private static final String AUDIO_FORMAT = "wav";
    private static final int SAMPLE_RATE = 24000;
    private static final int VOLUME = 50;
    private static final double RATE = 1.0;
    private static final double PITCH = 1.0;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TtsSettings settings;

    public CosyvoiceAdapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);

        String apiKey = TtsParams.str(cfg, "apiKey", "");
        if (apiKey.isEmpty()) {
            throw TtsException.notConfigured("CosyVoice 配置缺少 apiKey");
        }
        // 空串也要回落到官方域名：前端给的是默认值，但用户清空后保存的就是空串，
        // 拼出 "/services/audio/…" 这种相对地址只会在 URI.create 处炸得更难看
        String apiUrl = stripTrailingSlashes(TtsParams.str(cfg, "apiUrl", DEFAULT_API_URL));
        if (apiUrl.isEmpty()) {
            apiUrl = DEFAULT_API_URL;
        }
        String model = TtsParams.str(cfg, "model", DEFAULT_MODEL);
        String voiceId = TtsParams.str(cfg, "voiceId", "");
        if (voiceId.isEmpty()) {
            throw TtsException.notConfigured("CosyVoice 配置缺少 voiceId（云端音色）");
        }

        ObjectNode input = JSON.createObjectNode();
        input.put("text", text);
        input.put("voice", voiceId);
        input.put("format", AUDIO_FORMAT);
        input.put("sample_rate", SAMPLE_RATE);
        input.put("volume", VOLUME);
        input.put("rate", RATE);
        input.put("pitch", PITCH);

        // language_hints 是「候选语言」数组（官方要求数组形态）；留空则交给服务端自动判断
        String language = TtsParams.str(cfg, "language", "");
        if (!language.isEmpty()) {
            input.putArray("language_hints").add(language);
        }

        String dialect = TtsParams.str(cfg, "dialect", "");
        if (!dialect.isEmpty()) {
            input.put("instruction", instruction(dialect));
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.set("input", input);

        byte[] raw = TtsHttp.post(apiUrl + SYNTHESIS_PATH,
                null, Map.of("Authorization", "Bearer " + apiKey), body.toString(), settings.getTtsTimeout());
        return download(parse(raw));
    }

    /** 前端下拉值 → 指令文本：「四川话」→「用四川话说话。」。 */
    static String instruction(String dialect) {
        return "用" + dialect.trim() + "说话。";
    }

    private static String stripTrailingSlashes(String url) {
        String result = url.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static JsonNode parse(byte[] raw) {
        try {
            return JSON.readTree(raw);
        } catch (IOException | RuntimeException e) {
            // 200 但回了 HTML（网关拦截页）时会走到这里：直接把开头几个字节报出来，比 NPE 强
            throw TtsException.badResponse("CosyVoice 响应不是合法 JSON："
                    + new String(raw, 0, Math.min(120, raw.length), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * 校验 {@code finish_reason} 并取出 {@code output.audio.url} 下载音频
     * （上游 {@code cosyvoice.rs:102-119}）。
     *
     * <p>下载用 {@code getAllowEmpty} 但自己判空：把「空音频」当成功，前端就是 0 字节播放失败；
     * 而下载端点的 200+空体在 {@code get} 里会被判成 {@code BAD_RESPONSE}，
     * 那条消息说的是「TTS 上游返回了空响应体」，看不出是哪一步，所以这里自己给消息。
     */
    private byte[] download(JsonNode body) {
        JsonNode finishReason = body.path("output").path("finish_reason");
        if (!finishReason.isMissingNode() && !finishReason.isNull() && !"stop".equals(finishReason.asText())) {
            throw TtsException.badResponse("CosyVoice 合成未完成：finish_reason=" + finishReason.asText());
        }

        JsonNode audioUrl = body.path("output").path("audio").path("url");
        if (!audioUrl.isTextual() || audioUrl.asText().isBlank()) {
            throw TtsException.badResponse("CosyVoice 响应缺少 output.audio.url");
        }

        byte[] audio = TtsHttp.getAllowEmpty(audioUrl.asText(), null, settings.getTtsTimeout());
        if (audio.length == 0) {
            throw TtsException.badResponse("CosyVoice 音频下载为空：" + audioUrl.asText());
        }
        // 下载地址是服务端给的完整 URL，与 apiUrl 无关
        return audio;
    }
}

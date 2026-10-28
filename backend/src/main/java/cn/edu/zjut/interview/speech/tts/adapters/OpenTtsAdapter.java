package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenTTS（OpenAI 兼容 {@code /v1/audio/speech} 端点）适配器，对应上游
 * {@code adapters/opentts.rs}。默认指向硅基流动，默认模型 {@code FunAudioLLM/CosyVoice2-0.5B}。
 *
 * <p>协议：{@code POST {base}/v1/audio/speech}，四个字段
 * {@code model} / {@code input} / {@code voice} / {@code response_format}，响应体就是音频字节。
 *
 * <p>与上游的差异：
 * <ul>
 *   <li><b>地址规整顺序照抄上游</b>（{@code opentts.rs:70-77}）：先去尾斜杠、再去尾
 *       {@code /v1}、<b>再</b>去尾斜杠，最后补 {@code /v1/audio/speech}。顺序反了会把
 *       {@code https://x.cn/v1/} 拼成 {@code https://x.cn//v1/audio/speech}；</li>
 *   <li><b>{@code voice} 回落到全局 {@code ttsVoice}</b>：照 {@code voice_maker.rs:437-443}
 *       的「引擎级 voice 优先，为空回退全局音色标识」，本项目全局音色就是
 *       {@link TtsSettings#getTtsVoice()}；</li>
 *   <li><b>{@code language} 只记日志不发送</b>：见 {@link #generate} 里的说明。</li>
 * </ul>
 */
public class OpenTtsAdapter implements TtsAdapter {

    public static final String TYPE = "opentts";

    /** 上游 {@code voice_maker.rs:449-453}：地址为空时的回落值。 */
    private static final String DEFAULT_API_URL = "https://api.siliconflow.cn/v1";

    /** 上游 {@code voice_maker.rs:444-448}：模型为空时的回落值。 */
    private static final String DEFAULT_MODEL = "FunAudioLLM/CosyVoice2-0.5B";

    private static final Logger log = LoggerFactory.getLogger(OpenTtsAdapter.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TtsSettings settings;

    public OpenTtsAdapter(TtsSettings settings) {
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
            throw TtsException.notConfigured("OpenTTS 配置缺少 apiKey");
        }

        // 引擎级 voice 优先，为空回退全局 ttsVoice（voice_maker.rs:452-456 的同款回落）
        String voice = TtsParams.str(cfg, "voice", "");
        if (voice.isEmpty()) {
            voice = settings.getTtsVoice() == null ? "" : settings.getTtsVoice().trim();
        }
        if (voice.isEmpty()) {
            throw TtsException.notConfigured("OpenTTS 配置缺少 voice（音色标识）");
        }

        String model = TtsParams.str(cfg, "model", DEFAULT_MODEL);
        String endpoint = endpoint(TtsParams.str(cfg, "apiUrl", DEFAULT_API_URL));
        String responseFormat = TtsParams.str(cfg, "audioFormat", settings.getTtsAudioFormat());

        if (text == null || text.trim().isEmpty()) {
            // 上游 opentts.rs:55-57 是 anyhow!；这里归到 BAD_RESPONSE 是因为「请求根本不该发出」
            // （同 TtsService 对空文本的处理），前端文案走同一条
            throw TtsException.badResponse("OpenTTS 输入文本为空");
        }

        // language 只取不送：上游 opentts.rs:59-61 明确写了「OpenAI 兼容端点自动识别语言，
        // 加 [JA]/[ZH] 控制标记反而可能不兼容」，所以这里只进 debug 日志，请求体里没有它
        String language = TtsParams.str(cfg, "language", "");
        log.debug("[TTS] OpenTTS synthesize lang={} text={}", language, text);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", text);
        body.put("voice", voice);
        body.put("response_format", responseFormat);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);

        // 字节原样返回：response_format 可能是 mp3，校验 RIFF 会误杀
        return TtsHttp.post(endpoint, null, headers, toJson(body), settings.getTtsTimeout());
    }

    /**
     * 地址规整（上游 {@code opentts.rs:70-77}）：去尾斜杠 → 去尾 {@code /v1} → 再去尾斜杠 →
     * 补 {@code /v1/audio/speech}。
     *
     * <p>中间那步「去尾 {@code /v1}」用循环：Rust 的 {@code trim_end_matches} 会一直剥到
     * 没有为止，{@code https://x/v1/v1} 得到 {@code https://x}；只剥一次会拼出
     * {@code https://x/v1/v1/audio/speech}。
     */
    static String endpoint(String apiUrl) {
        String base = apiUrl == null ? "" : apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        while (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/audio/speech";
    }

    private static String toJson(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw TtsException.requestFailed("OpenTTS 请求体序列化失败：" + e.getMessage());
        }
    }
}

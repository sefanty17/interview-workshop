package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;

import java.util.Map;

/**
 * IndexTTS2 适配器，对应上游 {@code adapters/indextts.rs}（只做非流式那一版）。
 *
 * <p>协议：<b>GET</b> {@code {base}}（base 本身就是 {@code /voice/indextts/presets}），
 * 全部参数走查询串，响应体就是音频字节。
 *
 * <p>与上游的差异：
 * <ul>
 *   <li><b>地址要 {@link TtsParams#urlWithPort} 拼</b>：上游 {@code indextts_api_url} 直接就是
 *       完整路径，本项目前端把 host 与 port 拆成两个输入框（{@code SettingsTts.vue} 第 839-847 行），
 *       所以既要拼端口、又要防「用户已经填了完整路径」时重复拼——逻辑同 {@link FishS2Adapter}；</li>
 *   <li><b>{@code model} / {@code voice} / {@code referenceAudio} 三个前端字段不上送</b>：
 *       上游 {@code indextts.rs:57-77} 的请求里根本没有这三个参数（说话人只有 {@code id} 一个号），
 *       凭空加参数等于自造协议，目标服务是否认这三个名字无从验证
 *       （V5a 的 {@link VitsAdapter} 就是按同一把尺子砍掉了 noiseScale/lengthScale）。
 *       等有真实 IndexTTS 服务与协议文档再补；</li>
 *   <li><b>{@code id} 恒为 0</b>：前端 indextts2 表单没有「说话人 ID」输入框，
 *       {@code intVal(cfg,"speakerId",0)} 拿不到值，与上游 {@code speaker_id: 0} 一致。</li>
 * </ul>
 */
public class IndexTts2Adapter implements TtsAdapter {

    public static final String TYPE = "indextts2";

    /** 上游 {@code indextts.rs:34-39} 的默认地址（已含完整路径）。 */
    private static final String DEFAULT_API_URL = "http://127.0.0.1:23467";

    private static final String PRESETS_PATH = "/voice/indextts/presets";

    private final TtsSettings settings;

    public IndexTts2Adapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);
        String base = endpoint(TtsParams.urlWithPort(
                TtsParams.str(cfg, "apiUrl", DEFAULT_API_URL), TtsParams.intVal(cfg, "port", 0)));
        String language = TtsParams.str(cfg, "language", "zh");
        String audioFormat = audioFormat(settings.getTtsAudioFormat());

        // 顺序逐字照抄上游 indextts.rs:57-77；stream 是大写 F 的 "False"（Python 字面量）
        Map<String, String> query = TtsParams.query(
                "id", String.valueOf(TtsParams.intVal(cfg, "speakerId", 0)),
                "emo_control_method", "1",
                "emo_id", normalizeEmoLabel(emo),
                "vec1", "0.0",
                "vec2", "0.0",
                "vec3", "0.0",
                "vec4", "0.0",
                "vec5", "0.0",
                "vec6", "0.0",
                "vec7", "0.0",
                "vec8", "0.0",
                "emo_weight", "0.6",
                "stream", "False",
                "max_text_tokens_per_segment", "120",
                "quick_token", "0",
                "lang", language,
                "audio_format", audioFormat,
                "_verify", "0",
                "text", text);

        return TtsHttp.get(base, query, settings.getTtsTimeout());
    }

    /**
     * 端点点名：{@code urlWithPort} 之后若不是 {@code /voice/indextts/presets} 结尾就补上。
     *
     * <p>为什么要两道：用户可能在地址框里填 {@code http://host:23467}（要补路径），
     * 也可能照着上游文档填了完整路径（不能重复补，否则拼出两段）。
     */
    static String endpoint(String base) {
        String url = base == null ? "" : base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith(PRESETS_PATH) ? url : url + PRESETS_PATH;
    }

    /**
     * 情绪标签归一化（上游 {@code indextts.rs:44-49}）：trim 后 {@code 心动} → {@code 情动}，
     * 其余原样。服务端情绪表未覆盖的标签会安全回退为「跟随音色参考」，不会报错。
     */
    static String normalizeEmoLabel(String emo) {
        if (emo == null) {
            return "";
        }
        String trimmed = emo.trim();
        return "心动".equals(trimmed) ? "情动" : trimmed;
    }

    /** 全局 {@code ttsAudioFormat}；空值回落 wav（与上游 {@code audio_format: "wav"} 一致）。 */
    static String audioFormat(String configured) {
        String format = configured == null ? "" : configured.trim();
        return format.isEmpty() ? "wav" : format;
    }
}

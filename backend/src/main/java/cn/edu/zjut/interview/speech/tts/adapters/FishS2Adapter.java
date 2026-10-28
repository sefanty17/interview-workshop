package cn.edu.zjut.interview.speech.tts.adapters;

import cn.edu.zjut.interview.speech.tts.TtsAdapter;
import cn.edu.zjut.interview.speech.tts.TtsException;
import cn.edu.zjut.interview.speech.tts.TtsHttp;
import cn.edu.zjut.interview.speech.tts.TtsParams;
import cn.edu.zjut.interview.speech.tts.TtsSettings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fish Audio S2（s2.cpp 服务）适配器，对应上游 {@code adapters/fish_s2.rs}。
 *
 * <p>协议：{@code POST /generate}，{@code multipart/form-data} 三个文本字段
 * {@code voice} / {@code text} / {@code params}，返回 WAV 字节。
 *
 * <p>与上游的三处差异：
 * <ul>
 *   <li><b>支持 apiKey</b>：上游不发鉴权（s2.cpp 是本机服务）。本项目允许把
 *       {@code apiUrl} 指向远端部署（前端 fish-speech 表单也有 apiKey 输入框），
 *       配了密钥就带 {@code Authorization: Bearer …}，没配就完全与上游一致；</li>
 *   <li>参数从设置里读（{@code apiUrl} / {@code voice} / {@code apiKey}），
 *       上游是构造时注入；</li>
 *   <li>超时写死 300 秒而不是用 {@code ttsTimeout}：见 {@link #REQUEST_TIMEOUT_SECONDS}。</li>
 * </ul>
 */
public class FishS2Adapter implements TtsAdapter {

    public static final String TYPE = "fish-speech";

    /**
     * 固定 300 秒，<b>不看</b> {@code ttsTimeout}：上游 {@code fish_s2.rs:20} 就是
     * {@code Duration::from_secs(300)}。长文本 + 本机 CPU 推理本来就慢，用设置页默认的
     * 30 秒会把「正在推理」判成超时，用户看到的是「语音服务偶尔失效」。
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 300;

    /**
     * 全局串行闸：s2.cpp 一次只处理一个生成请求（上游 {@code fish_s2.rs:22-24}
     * 的 {@code REQUEST_GATE}）。
     *
     * <p>必须 static，理由同 {@code GsvAdapter#LOCKS}：适配器每次合成现造，
     * 实例锁拦不住并发。这里是全局一把（上游也是全局），因为 s2.cpp 本身无并发能力。
     */
    private static final ReentrantLock GATE = new ReentrantLock();

    /** 上游 {@code fish_s2.rs:104-109} 的固定推理参数，原样作为 {@code params} 字段的 JSON 串。 */
    private static final String PARAMS_JSON =
            "{\"max_new_tokens\":512,\"temperature\":0.8,\"top_p\":0.9,\"top_k\":40}";

    /**
     * 情绪 → S2 英文标签。逐条照 {@code fish_s2.rs:56-85} 抄，<b>20 组</b>
     * （最后三组 calm / gentle / playful 之后没有别的分支，{@code _ => None}）。
     *
     * <p>为什么不用一张「中文 → 标签」的小表：上游把同义词（中英混排、joy/joyful、
     * cry/crying/sob/sobbing）逐一列了，漏一个同义词就等于用户的情绪设置静默失效；
     * 抄全比事后按报障补快。
     */
    private static final Map<String, String> EMOTIONS = emotions();

    private final TtsSettings settings;

    public FishS2Adapter(TtsSettings settings) {
        this.settings = settings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public byte[] generate(String text, String emo) {
        Map<String, Object> cfg = settings.engineConfig(TYPE);
        String apiUrl = TtsParams.str(cfg, "apiUrl", "");
        if (apiUrl.isEmpty()) {
            throw TtsException.notConfigured("Fish S2 配置缺少 apiUrl");
        }
        // 上游在构造函数里就拦（fish_s2.rs:37-39）：voice 是必填的音色标识，没有它服务端
        // 只会回 400，本地先报能指名缺哪个字段
        String voice = TtsParams.str(cfg, "voice", "");
        if (voice.isEmpty()) {
            throw TtsException.notConfigured("Fish S2 配置缺少 voice（音色标识）");
        }

        String apiKey = TtsParams.str(cfg, "apiKey", "");
        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("voice", voice);
        fields.put("text", textWithEmotion(text, emo));
        fields.put("params", PARAMS_JSON);

        GATE.lock();
        try {
            byte[] audio = TtsHttp.postMultipart(endpoint(apiUrl), fields, headers, REQUEST_TIMEOUT_SECONDS);
            checkWav(audio);
            return audio;
        } finally {
            GATE.unlock();
        }
    }

    /**
     * 端点拼接（上游 {@code fish_s2.rs:47-53}）：去尾斜杠后已以 {@code /generate} 结尾就原样用，
     * 否则补上。前端填 {@code http://host:3030/generate} 或 {@code http://host:3030/} 都能用。
     */
    static String endpoint(String apiUrl) {
        String base = apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/generate") ? base : base + "/generate";
    }

    /**
     * 情绪标签前置：{@code "[happy]你好"}。
     *
     * <p>文本已经以 {@code [} 开头时不加前缀（上游 {@code fish_s2.rs:90}）：
     * 调用方自己写了 {@code "[sad]…"} 这类标签时，再加一层会让 S2 读出不存在的标记。
     */
    static String textWithEmotion(String text, String emo) {
        String trimmed = text == null ? "" : text.trim();
        String tag = normalizeEmotion(emo);
        if (tag != null && !trimmed.startsWith("[")) {
            return "[" + tag + "]" + trimmed;
        }
        return trimmed;
    }

    /** 未识别 / 空情绪返回 null（上游 {@code _ => None}）。 */
    static String normalizeEmotion(String emo) {
        if (emo == null) {
            return null;
        }
        return EMOTIONS.get(emo.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static Map<String, String> emotions() {
        Map<String, String> map = new LinkedHashMap<>();
        put(map, "happy", "happy", "happiness", "joy", "joyful", "高兴", "开心");
        put(map, "excited", "excited", "excitement", "兴奋");
        put(map, "sad", "sad", "sadness", "depressed", "悲伤", "伤心");
        put(map, "angry", "angry", "anger", "生气");
        put(map, "fearful", "fear", "fearful", "scared", "害怕");
        put(map, "surprised", "surprise", "surprised", "惊讶");
        put(map, "disgusted", "disgust", "disgusted", "厌恶");
        put(map, "whisper", "whisper", "whispering", "耳语", "低语");
        put(map, "crying", "cry", "crying", "sob", "sobbing", "哭泣", "大哭", "哭腔");
        put(map, "nervous", "nervous", "慌张", "紧张");
        put(map, "worried", "worried", "担心");
        put(map, "embarrassed", "embarrassed", "尴尬", "难为情", "羞耻");
        put(map, "confident", "confident", "自信");
        put(map, "shy", "shy", "害羞");
        put(map, "serious", "serious", "认真", "正经");
        put(map, "confused", "confused", "疑惑");
        put(map, "affectionate", "affectionate", "情动", "心动");
        put(map, "playful", "playful", "调皮");
        put(map, "calm", "calm", "平静");
        put(map, "gentle", "gentle", "温柔");
        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, String> map, String tag, String... aliases) {
        for (String alias : aliases) {
            map.put(alias, tag);
        }
    }

    /**
     * 校验响应是 WAV（上游 {@code fish_s2.rs:135-137}）：长度 ≥ 12、前 4 字节 {@code RIFF}、
     * 8..12 是 {@code WAVE}。
     *
     * <p>为什么要判：s2.cpp 出错时可能回一段 JSON 文本而状态码仍是 200，直接返回给前端
     * 会让播放器拿到一段以 {@code {} 开头} 的「音频」，表现为静默失败。
     */
    private static void checkWav(byte[] audio) {
        boolean riff = audio.length >= 4
                && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F';
        boolean wave = audio.length >= 12
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E';
        if (audio.length < 12 || !riff || !wave) {
            throw TtsException.badResponse("Fish S2 返回的不是合法 WAV（前 12 字节："
                    + firstBytes(audio) + "）");
        }
    }

    /** 取前 12 字节的可读形式：可打印 ASCII 原样、其余用点号，一眼能看出是 JSON 还是二进制。 */
    private static String firstBytes(byte[] audio) {
        int length = Math.min(12, audio.length);
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int value = audio[i] & 0xFF;
            text.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
        }
        return text.toString();
    }
}

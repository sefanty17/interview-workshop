package cn.edu.zjut.interview.speech.tts;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TTS 设置：<strong>camelCase，与前端 {@code SettingsTts.vue} 的字段逐字对齐</strong>。
 *
 * <p>为什么不像 {@code AsrSettings} 那样用 snake_case：ASR 的结构体是上游 Rust
 * {@code settings.rs::AsrSettings} 的 1:1 投影，serde 原样输出 snake_case；
 * TTS 上游<b>没有</b>对应的设置结构体（原版把 tts 参数散在 {@code SETTINGS} 的
 * 一堆独立 key 里，没有聚合对象），所以这里的唯一契约就是前端：
 * {@code frontend/src/data/mock.ts} 的 {@code Settings} 接口（第 277-311 行）
 * 与默认值对象（第 675-808 行）。用 camelCase 后，将来 V6 接前端时
 * {@code store.settings} 可以直接投影，不用逐字段重命名。
 *
 * <p>字段一个不多一个不少地照 {@code mock.ts} 抄：那里有 {@code ttsPitch} /
 * {@code ttsVolume} 之类的「设置页全局参数」——本批四个适配器并不都用到，
 * 但少了它们前端 PUT 上来的值就会被 Jackson 丢掉（读回来变默认值），
 * 等于静默篡改用户设置。用不到的字段先存着，执行留给后续批次。
 *
 * <p>默认值全部取自 {@code mock.ts} 的默认设置对象，不自己发挥：
 * 前端首屏渲染的初值就是这份数据，后端给的默认值必须与之一致，
 * 否则「没存过设置」和「存过默认设置」会显示成两套不同的表单。
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public class TtsSettings {

    /** 默认引擎。与 mock.ts 一致是 edge-tts（虽然它还没接入，见 {@code TtsEngineCatalog}）。 */
    public static final String DEFAULT_TYPE = "edge-tts";

    private boolean ttsOn = false;
    private String ttsType = DEFAULT_TYPE;

    // ---- 设置页「全局参数」区：mock.ts 675-708 ----
    private double ttsSpeed = 1.0;
    private String ttsVoice = "zh-CN-YunxiNeural";
    private int ttsPitch = 0;
    private int ttsVolume = 100;
    private String ttsLanguage = "zh-CN";
    private String ttsEmotion = "中立";
    private String ttsApiUrl = "http://127.0.0.1";
    private String ttsApiKey = "";
    private int ttsPort = 9880;
    private String ttsModel = "";
    private int ttsSpeakerId = 0;
    private int ttsStyleId = 0;
    private String ttsReferenceAudio = "";
    private String ttsReferenceText = "";
    /** 全局音频格式：sbv2 / bv2 / vits 上游都把它当 provider 级参数，不是引擎配置里的字段。 */
    private String ttsAudioFormat = "wav";
    private String ttsDialect = "";
    private double ttsLengthScale = 1.0;
    private double ttsSdpRatio = 0.2;
    private double ttsNoiseScale = 0.667;
    private int ttsTimeout = 30;
    private int ttsConcurrency = 2;
    private boolean ttsCacheEnabled = true;
    private long ttsCacheLimit = 512;
    private int ttsRetryCount = 2;

    // ---- 音色复刻区：mock.ts 701-708 ----
    private String ttsCloneName = "";
    private String ttsCloneSample = "";
    private String ttsCloneText = "";
    private String ttsCloneLanguage = "zh";
    private String ttsImportVoiceId = "";
    private String ttsStyleVectorsTarget = "local-interviewer-female";
    private String ttsPreviewText = "你好，欢迎参加今天的模拟面试。";
    private double ttsPreviewSdp = 0.2;

    /**
     * 每个引擎自己的一组配置，键是 {@code ttsType} 的取值。
     *
     * <p>值只能是 string / number / boolean（前端定义见 mock.ts 第 311 行），
     * 所以这里用 {@code Map<String, Object>}；读的时候一律走 {@link TtsParams}，
     * 不直接强转。
     */
    private Map<String, Map<String, Object>> ttsEngineConfigs = defaultEngineConfigs();

    public static TtsSettings defaults() {
        return new TtsSettings();
    }

    /** 取某引擎的配置；没有就返回空 Map（适配器不必判空，缺键各自走默认值）。 */
    public Map<String, Object> engineConfig(String type) {
        Map<String, Object> config = type == null ? null : ttsEngineConfigs.get(type);
        return config == null ? new LinkedHashMap<>() : config;
    }

    public boolean isTtsOn() { return ttsOn; }
    public void setTtsOn(boolean ttsOn) { this.ttsOn = ttsOn; }

    public String getTtsType() { return ttsType; }
    public void setTtsType(String ttsType) { this.ttsType = ttsType; }

    public double getTtsSpeed() { return ttsSpeed; }
    public void setTtsSpeed(double ttsSpeed) { this.ttsSpeed = ttsSpeed; }

    public String getTtsVoice() { return ttsVoice; }
    public void setTtsVoice(String ttsVoice) { this.ttsVoice = ttsVoice; }

    public int getTtsPitch() { return ttsPitch; }
    public void setTtsPitch(int ttsPitch) { this.ttsPitch = ttsPitch; }

    public int getTtsVolume() { return ttsVolume; }
    public void setTtsVolume(int ttsVolume) { this.ttsVolume = ttsVolume; }

    public String getTtsLanguage() { return ttsLanguage; }
    public void setTtsLanguage(String ttsLanguage) { this.ttsLanguage = ttsLanguage; }

    public String getTtsEmotion() { return ttsEmotion; }
    public void setTtsEmotion(String ttsEmotion) { this.ttsEmotion = ttsEmotion; }

    public String getTtsApiUrl() { return ttsApiUrl; }
    public void setTtsApiUrl(String ttsApiUrl) { this.ttsApiUrl = ttsApiUrl; }

    public String getTtsApiKey() { return ttsApiKey; }
    public void setTtsApiKey(String ttsApiKey) { this.ttsApiKey = ttsApiKey; }

    public int getTtsPort() { return ttsPort; }
    public void setTtsPort(int ttsPort) { this.ttsPort = ttsPort; }

    public String getTtsModel() { return ttsModel; }
    public void setTtsModel(String ttsModel) { this.ttsModel = ttsModel; }

    public int getTtsSpeakerId() { return ttsSpeakerId; }
    public void setTtsSpeakerId(int ttsSpeakerId) { this.ttsSpeakerId = ttsSpeakerId; }

    public int getTtsStyleId() { return ttsStyleId; }
    public void setTtsStyleId(int ttsStyleId) { this.ttsStyleId = ttsStyleId; }

    public String getTtsReferenceAudio() { return ttsReferenceAudio; }
    public void setTtsReferenceAudio(String ttsReferenceAudio) { this.ttsReferenceAudio = ttsReferenceAudio; }

    public String getTtsReferenceText() { return ttsReferenceText; }
    public void setTtsReferenceText(String ttsReferenceText) { this.ttsReferenceText = ttsReferenceText; }

    public String getTtsAudioFormat() { return ttsAudioFormat; }
    public void setTtsAudioFormat(String ttsAudioFormat) { this.ttsAudioFormat = ttsAudioFormat; }

    public String getTtsDialect() { return ttsDialect; }
    public void setTtsDialect(String ttsDialect) { this.ttsDialect = ttsDialect; }

    public double getTtsLengthScale() { return ttsLengthScale; }
    public void setTtsLengthScale(double ttsLengthScale) { this.ttsLengthScale = ttsLengthScale; }

    public double getTtsSdpRatio() { return ttsSdpRatio; }
    public void setTtsSdpRatio(double ttsSdpRatio) { this.ttsSdpRatio = ttsSdpRatio; }

    public double getTtsNoiseScale() { return ttsNoiseScale; }
    public void setTtsNoiseScale(double ttsNoiseScale) { this.ttsNoiseScale = ttsNoiseScale; }

    public int getTtsTimeout() { return ttsTimeout; }
    public void setTtsTimeout(int ttsTimeout) { this.ttsTimeout = ttsTimeout; }

    public int getTtsConcurrency() { return ttsConcurrency; }
    public void setTtsConcurrency(int ttsConcurrency) { this.ttsConcurrency = ttsConcurrency; }

    public boolean isTtsCacheEnabled() { return ttsCacheEnabled; }
    public void setTtsCacheEnabled(boolean ttsCacheEnabled) { this.ttsCacheEnabled = ttsCacheEnabled; }

    public long getTtsCacheLimit() { return ttsCacheLimit; }
    public void setTtsCacheLimit(long ttsCacheLimit) { this.ttsCacheLimit = ttsCacheLimit; }

    public int getTtsRetryCount() { return ttsRetryCount; }
    public void setTtsRetryCount(int ttsRetryCount) { this.ttsRetryCount = ttsRetryCount; }

    public String getTtsCloneName() { return ttsCloneName; }
    public void setTtsCloneName(String ttsCloneName) { this.ttsCloneName = ttsCloneName; }

    public String getTtsCloneSample() { return ttsCloneSample; }
    public void setTtsCloneSample(String ttsCloneSample) { this.ttsCloneSample = ttsCloneSample; }

    public String getTtsCloneText() { return ttsCloneText; }
    public void setTtsCloneText(String ttsCloneText) { this.ttsCloneText = ttsCloneText; }

    public String getTtsCloneLanguage() { return ttsCloneLanguage; }
    public void setTtsCloneLanguage(String ttsCloneLanguage) { this.ttsCloneLanguage = ttsCloneLanguage; }

    public String getTtsImportVoiceId() { return ttsImportVoiceId; }
    public void setTtsImportVoiceId(String ttsImportVoiceId) { this.ttsImportVoiceId = ttsImportVoiceId; }

    public String getTtsStyleVectorsTarget() { return ttsStyleVectorsTarget; }
    public void setTtsStyleVectorsTarget(String ttsStyleVectorsTarget) { this.ttsStyleVectorsTarget = ttsStyleVectorsTarget; }

    public String getTtsPreviewText() { return ttsPreviewText; }
    public void setTtsPreviewText(String ttsPreviewText) { this.ttsPreviewText = ttsPreviewText; }

    public double getTtsPreviewSdp() { return ttsPreviewSdp; }
    public void setTtsPreviewSdp(double ttsPreviewSdp) { this.ttsPreviewSdp = ttsPreviewSdp; }

    public Map<String, Map<String, Object>> getTtsEngineConfigs() { return ttsEngineConfigs; }
    public void setTtsEngineConfigs(Map<String, Map<String, Object>> ttsEngineConfigs) {
        this.ttsEngineConfigs = ttsEngineConfigs == null ? new LinkedHashMap<>() : ttsEngineConfigs;
    }

    /**
     * 13 个引擎的默认配置，逐字照 {@code mock.ts} 第 709-812 行。
     *
     * <p>为什么要带一套默认值而不是空 Map：前端设置页切引擎时直接读
     * {@code ttsEngineConfigs[type]}，拿到 undefined 会渲染成全空表单，
     * 用户会以为「刚装的程序配置全丢了」。
     */
    private static Map<String, Map<String, Object>> defaultEngineConfigs() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        all.put("edge-tts", cfg(
                "language", "zh-CN", "voice", "zh-CN-YunxiNeural", "speed", 1, "pitch", 0, "volume", 100));
        all.put("gpt-sovits", cfg(
                "apiUrl", "http://127.0.0.1", "port", 9880,
                "gptModel", "GPT_weights_v2/interviewer.ckpt",
                "sovitsModel", "SoVITS_weights_v2/interviewer.pth",
                "referenceAudio", "", "referenceText", "", "language", "zh", "emotion", "中立"));
        all.put("sbv2", cfg(
                "apiUrl", "http://127.0.0.1:5000", "modelName", "interviewer",
                "speakerId", 0, "styleId", 0, "language", "zh", "speed", 1));
        all.put("sbv2api", cfg(
                "apiUrl", "http://127.0.0.1:5000", "modelName", "interviewer",
                "speakerId", 0, "styleId", 0, "speed", 1));
        all.put("cosyvoice", cfg(
                "apiUrl", "https://dashscope.aliyuncs.com/api/v1", "apiKey", "",
                "model", "cosyvoice-v3.5-flash", "voiceId", "", "language", "zh", "dialect", ""));
        all.put("indextts2", cfg(
                "apiUrl", "http://127.0.0.1", "port", 7860, "model", "IndexTTS-2",
                "voice", "interviewer", "language", "zh", "emotion", "中立", "referenceAudio", ""));
        all.put("fish-speech", cfg(
                "apiUrl", "http://127.0.0.1:8080", "apiKey", "", "model", "fish-speech-1.5",
                "voice", "paimeng", "language", "zh", "emotion", "中立", "referenceAudio", ""));
        all.put("opentts", cfg(
                "apiUrl", "https://api.siliconflow.cn/v1", "apiKey", "",
                "model", "FunAudioLLM/CosyVoice2-0.5B", "voice", "", "language", "zh", "audioFormat", "wav"));
        all.put("vits", cfg(
                "apiUrl", "http://127.0.0.1", "port", 23456, "speakerId", 0,
                "language", "zh", "noiseScale", 0.667, "lengthScale", 1));
        all.put("aivis", cfg(
                "apiUrl", "https://api.aivis-project.com/v1", "apiKey", "", "modelUuid", "",
                "speakerId", 0, "speed", 1, "intonation", 1));
        all.put("bv2", cfg(
                "apiUrl", "http://127.0.0.1:5000", "speakerId", 0, "styleId", 0,
                "language", "zh", "sdpRatio", 0.2));
        all.put("gsv", cfg(
                "apiUrl", "http://127.0.0.1:9880", "referenceText", "", "referenceAudio", "",
                "gptModel", "", "sovitsModel", "", "language", "zh", "emotion", "中立"));
        all.put("local-sbv2", cfg(
                "voiceId", "local-interviewer-female", "speakerId", 0, "styleId", 0,
                "lengthScale", 1, "sdpRatio", 0.2, "fallbackModel", "", "fallbackSpeakerId", ""));
        return all;
    }

    private static Map<String, Object> cfg(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

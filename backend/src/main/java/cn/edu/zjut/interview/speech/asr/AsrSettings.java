package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASR 全局设置，字段 1:1 对齐 {@code settings.rs::AsrSettings}。
 *
 * <p>JSON 键用 <strong>snake_case</strong>：上游结构体没有 {@code rename_all}，
 * 序列化出来就是 Rust 字段名，LingChat 前端 {@code api/services/asr.ts} 也这么读。
 *
 * <p>与原版的三处差异（都是「本项目的存储形态不同」导致的，不是语义改动）：
 * <ol>
 *   <li>原版把设置拆成 {@code ASR_PROVIDERS} / {@code ASR_ACTIVE_PROVIDER_ID} /
 *       {@code ASR_PREFS} 三个 store key 分别读写；本项目整份 JSON 存一行
 *       （见 {@code SpeechSetting}），所以这里合成一个对象；</li>
 *   <li>原版 {@code load()} 用 defaults 起步再逐段覆盖，所以「老数据缺字段」有兜底语义；
 *       本项目存的就是整份对象，字段缺失由下面的字段初始值兜底，语义等价；</li>
 *   <li>原版的野值修复（active_provider 不在注册表就回退）在 {@code load} 里做，
 *       本项目放在 {@link AsrSettingsService#load()}。</li>
 * </ol>
 *
 * <p>字段初始值即默认值（不另写 {@code defaults()} 之外的第二份）：Jackson 反序列化
 * 缺失键时不会动字段，于是「前端只 PUT 两个字段」也能得到完整设置对象。
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public class AsrSettings {

    /** 默认 provider：设置页下拉的第一项，也是野值回退目标。 */
    public static final String DEFAULT_PROVIDER = "qwen-asr";

    /** 识别完成后只填入输入框，由用户自己按发送。 */
    public static final String SEND_MODE_FILL_ONLY = "fill_only";
    /** 识别完成后直接发送。 */
    public static final String SEND_MODE_AUTO_SEND = "auto_send";

    /** {@code sendMode} 的合法取值。原版是 enum，serde 只认这两个串。 */
    public static final List<String> SEND_MODES = List.of(SEND_MODE_FILL_ONLY, SEND_MODE_AUTO_SEND);

    @JsonProperty("active_provider")
    private String activeProvider = DEFAULT_PROVIDER;

    @JsonProperty("auto_listen")
    private boolean autoListen = false;

    @JsonProperty("send_mode")
    private String sendMode = SEND_MODE_FILL_ONLY;

    @JsonProperty("stream_enabled")
    private boolean streamEnabled = false;

    @JsonProperty("voice_input_enabled")
    private boolean voiceInputEnabled = false;

    /** VAD 静音计时（毫秒）：停止说话后静音该时长才结束一轮录音。 */
    @JsonProperty("vad_silence_ms")
    private int vadSilenceMs = 800;

    /** 能量监测启动缓冲期（毫秒）：TTS 播完恢复监听后该时长内不触发录音（0 = 无缓冲）。 */
    @JsonProperty("energy_warmup_ms")
    private int energyWarmupMs = 100;

    /** 是否输出逐帧 VAD 能量日志。默认关：录音期间每秒一条，只在排查「为什么不触发」时开。 */
    @JsonProperty("vad_debug_log")
    private boolean vadDebugLog = false;

    /**
     * 语音输入快捷键，存 {@code ShortcutBinding} 序列化 JSON
     * （如 {@code {"key":"f8"}} / {@code {"key":"f8","ctrl":true}}），默认裸 F8。
     */
    @JsonProperty("ptt_key")
    private String pttKey = "{\"key\":\"f8\"}";

    /** 失去焦点（全局）快捷键是否可用。默认关：全局注册是操作系统级按键抢占。 */
    @JsonProperty("ptt_global")
    private boolean pttGlobal = false;

    /** provider id → 该 provider 的配置。默认按注册表填齐占位，设置页打开就能渲染。 */
    @JsonProperty("provider_configs")
    private Map<String, AsrProviderConfig> providerConfigs = new LinkedHashMap<>();

    /**
     * 全新用户的默认设置：providerConfigs 按注册表填齐占位，前端拿到就能渲染。
     *
     * <p><b>注意 {@code voiceInputEnabled} 是 false，不要「修正」成 true</b>：
     * 上游 {@code AsrPrefs} 里同名字段的 serde 默认是 true，那是<strong>老数据迁移</strong>
     * 的兜底（旧 settings.json 没有这个字段，缺省不能让老用户被意外禁用）。
     * 而 {@code AsrSettings::defaults()}——也就是全新用户——是 false。
     * 本批存的是整份对象，不存在「老数据缺字段」这一层，所以只实现后者。
     */
    public static AsrSettings defaults() {
        AsrSettings settings = new AsrSettings();
        Map<String, AsrProviderConfig> configs = new LinkedHashMap<>();
        for (String providerId : AsrRegistry.PROVIDER_ORDER) {
            configs.put(providerId, new AsrProviderConfig());
        }
        settings.providerConfigs = configs;
        return settings;
    }

    public String getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(String activeProvider) {
        this.activeProvider = activeProvider == null ? "" : activeProvider;
    }

    public boolean isAutoListen() {
        return autoListen;
    }

    public void setAutoListen(boolean autoListen) {
        this.autoListen = autoListen;
    }

    public String getSendMode() {
        return sendMode;
    }

    public void setSendMode(String sendMode) {
        this.sendMode = sendMode == null ? "" : sendMode;
    }

    public boolean isStreamEnabled() {
        return streamEnabled;
    }

    public void setStreamEnabled(boolean streamEnabled) {
        this.streamEnabled = streamEnabled;
    }

    public boolean isVoiceInputEnabled() {
        return voiceInputEnabled;
    }

    public void setVoiceInputEnabled(boolean voiceInputEnabled) {
        this.voiceInputEnabled = voiceInputEnabled;
    }

    public int getVadSilenceMs() {
        return vadSilenceMs;
    }

    public void setVadSilenceMs(int vadSilenceMs) {
        this.vadSilenceMs = vadSilenceMs;
    }

    public int getEnergyWarmupMs() {
        return energyWarmupMs;
    }

    public void setEnergyWarmupMs(int energyWarmupMs) {
        this.energyWarmupMs = energyWarmupMs;
    }

    public boolean isVadDebugLog() {
        return vadDebugLog;
    }

    public void setVadDebugLog(boolean vadDebugLog) {
        this.vadDebugLog = vadDebugLog;
    }

    public String getPttKey() {
        return pttKey;
    }

    public void setPttKey(String pttKey) {
        this.pttKey = pttKey == null ? "" : pttKey;
    }

    public boolean isPttGlobal() {
        return pttGlobal;
    }

    public void setPttGlobal(boolean pttGlobal) {
        this.pttGlobal = pttGlobal;
    }

    public Map<String, AsrProviderConfig> getProviderConfigs() {
        return providerConfigs;
    }

    public void setProviderConfigs(Map<String, AsrProviderConfig> providerConfigs) {
        this.providerConfigs = providerConfigs == null ? new LinkedHashMap<>() : providerConfigs;
    }
}

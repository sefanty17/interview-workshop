package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ASR 会话状态机，对齐上游 {@code session.rs::AsrSession} 的 start / stop / cancel / 当前来源。
 *
 * <p>只做「一轮监听是否正在进行」的互斥，不含 VAD、不含音频传输、不含识别调用——
 * 那些是 V2（VAD）与 V3（录音上传）的事。V1 先把状态机立起来，前端才能画「正在听」。
 *
 * <p>为什么用 {@code synchronized} 而不是更像上游的显式锁对象：上游用
 * {@code tokio::Mutex} 是因为它的 start 里有异步（VAD reset）；这里全是内存操作，
 * 方法级同步就够了，也不会有把锁带出临界区的可能。
 */
@Service
public class AsrSessionService {

    /**
     * 会话来源。取值照上游 {@code AsrSource}：{@code button}（用户按键/按住说话）与
     * {@code auto}（自动监听）。上游没有单独的 ptt 来源——按住说话走的就是 button 路径。
     */
    public static final String SOURCE_BUTTON = "button";
    public static final String SOURCE_AUTO = "auto";
    public static final List<String> SOURCES = List.of(SOURCE_BUTTON, SOURCE_AUTO);

    /**
     * 一个进行中的会话。
     *
     * @param sessionId 会话标识（V1 只用于日志与前端去重）
     * @param source    来源，见 {@link #SOURCES}
     * @param startedAt 开始时刻
     */
    public record ActiveSession(String sessionId, String source, Instant startedAt) {
    }

    /**
     * 状态快照。
     *
     * <p><strong>字段分两组，别混为一谈</strong>：
     * <ol>
     *   <li><b>契约字段（与上游对齐）</b>：{@code vad_loaded} 与 {@code ptt_global_ok}。
     *       上游 {@code api/services/asr.ts::AsrStatus} 只有这两个，前端设置页的状态面板
     *       直接按这两个键渲染；键名必须是 snake_case——本项目没有全局命名策略，
     *       所以每个字段都显式标 {@link JsonProperty}，少标一个前端就拿不到值；</li>
     *   <li><b>本项目扩展</b>：{@code active_provider} / {@code active_source} /
     *       {@code session_id} / {@code provider_ready}。上游状态由桌面端自己维护，
     *       不需要回给前端；本项目会话在服务端，前端要画「正在听」就得知道这几个值。
     *       前端不读它们也不会坏（多出来的键被忽略）。</li>
     * </ol>
     *
     * <p>为什么没有 {@code locked}：它恒为 false 且上游没有这个概念，留着只会让人以为
     * 存在「会话结束后短暂锁定的窗口」。
     *
     * @param vadLoaded      VAD 是否就绪。<b>恒 {@code true}</b>——上游是「Silero 模型加载成功」，
     *                       本项目是能量门控（{@link EnergyVad}，纯 Java、无模型文件、无原生库），
     *                       不存在「加载失败」这种状态，所以恒可用。键名与语义不变，
     *                       前端据此决定「自动监听」能不能点。
     * @param pttGlobalOk    全局快捷键注册是否健康。<b>恒 {@code false}</b>，原因是实现方式不同、
     *                       不是功能没做：上游是桌面端注册操作系统级全局热键，浏览器拿不到
     *                       窗口失焦时的按键，本项目的 PTT 降级为页面内快捷键，根本不存在
     *                       「OS 级注册成功」这个状态（见
     *                       {@code .workbuddy/plans/语音线-ASR与TTS实施计划.md} §2.4）
     * @param activeProvider 当前生效的 provider id
     * @param activeSource   当前会话来源；无会话时为 {@code null}
     * @param sessionId      当前会话 id；无会话时为 {@code null}
     * @param providerReady  当前 provider 是否「现在就能识别」（见 {@code providerReady()}）
     */
    public record AsrStatus(
            @JsonProperty("vad_loaded") boolean vadLoaded,
            @JsonProperty("ptt_global_ok") boolean pttGlobalOk,
            @JsonProperty("active_provider") String activeProvider,
            @JsonProperty("active_source") String activeSource,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("provider_ready") boolean providerReady) {
    }

    private final AsrSettingsService settingsService;
    private final AsrRegistry registry;

    /** 当前会话；{@code null} = 没有会话。整个对象由 this 的监视器保护。 */
    private ActiveSession active;

    public AsrSessionService(AsrSettingsService settingsService, AsrRegistry registry) {
        this.settingsService = settingsService;
        this.registry = registry;
    }

    /**
     * 开一轮监听；已有会话 → {@link AsrErrorKind#SESSION_BUSY} / 409。
     *
     * <p>互斥是必须的：两路同时录音会让 VAD 隐状态互相污染（上游注释里的「双窗口」问题），
     * 而症状是「识别结果偶尔串台」，极难查。
     */
    public synchronized ActiveSession start(String source) {
        if (active != null) {
            throw AsrException.sessionBusy();
        }
        // 为什么归到 INVALID_SETTINGS：source 是请求参数里写错的值，前端要能凭 code 查人话；
        // 上游没有这个分支——它的 AsrSource 是 Rust 枚举，非法值在 serde 反序列化阶段就失败了，
        // 而我们收的是 String（前端 query/body 直传），只能在业务层自己挡。
        if (source == null || !SOURCES.contains(source)) {
            throw new AsrException(AsrErrorKind.INVALID_SETTINGS, HttpStatus.BAD_REQUEST,
                    "source 只能是 " + String.join(" 或 ", SOURCES));
        }
        active = new ActiveSession(UUID.randomUUID().toString(), source, Instant.now());
        return active;
    }

    /** 结束当前会话；无会话时返回 empty（幂等，重复调用不报错）。 */
    public synchronized Optional<ActiveSession> stop() {
        Optional<ActiveSession> ended = Optional.ofNullable(active);
        active = null;
        return ended;
    }

    /** 取消当前会话；无会话时什么也不做（幂等）。 */
    public synchronized void cancel() {
        active = null;
    }

    /** 当前状态快照。 */
    public synchronized AsrStatus status() {
        AsrSettings settings = settingsService.load();
        String providerId = settings.getActiveProvider();
        return new AsrStatus(
                // VAD 就绪恒 true：能量门控是无依赖算法（见 AsrStatus#vadLoaded）。
                // 上游这里是「模型加载成功」，我们是「不存在加载这一步」——恒可用。
                true,
                // 浏览器没有 OS 级全局热键，见 AsrStatus#pttGlobalOk
                false,
                providerId,
                active == null ? null : active.source(),
                active == null ? null : active.sessionId(),
                providerReady(providerId, settings));
    }

    /**
     * 「现在就能识别」的 V1 判据。
     *
     * <p>mock 恒可（它不依赖任何外部服务）；其余 provider 只看当前地域的 Key 是否为空——
     * 这一步刻意不查 {@code registry} 之外的网络可达性，V1 不做真实调用。
     */
    private boolean providerReady(String providerId, AsrSettings settings) {
        if (MockAsrProvider.ID.equals(providerId)) {
            return true;
        }
        if (!registry.contains(providerId)) {
            return false;
        }
        AsrProviderConfig config = settings.getProviderConfigs().get(providerId);
        return config != null && !config.effectiveApiKey().isBlank();
    }
}

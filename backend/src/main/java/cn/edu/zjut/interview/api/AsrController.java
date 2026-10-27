package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.speech.asr.AsrErrorKind;
import cn.edu.zjut.interview.speech.asr.AsrException;
import cn.edu.zjut.interview.speech.asr.AsrModelInfo;
import cn.edu.zjut.interview.speech.asr.AsrOptions;
import cn.edu.zjut.interview.speech.asr.AsrProvider;
import cn.edu.zjut.interview.speech.asr.AsrProviderConfig;
import cn.edu.zjut.interview.speech.asr.AsrProviderInfo;
import cn.edu.zjut.interview.speech.asr.AsrRegistry;
import cn.edu.zjut.interview.speech.asr.AsrResult;
import cn.edu.zjut.interview.speech.asr.AsrSessionService;
import cn.edu.zjut.interview.speech.asr.AsrSettings;
import cn.edu.zjut.interview.speech.asr.AsrSettingsService;
import cn.edu.zjut.interview.speech.asr.EnergyVad;
import cn.edu.zjut.interview.speech.asr.VadEventView;
import cn.edu.zjut.interview.speech.asr.VadSegmenter;
import cn.edu.zjut.interview.speech.asr.WavCodec;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 语音识别接口：设置读写 + 元数据 + 会话状态 + 探活（V1）、VAD / 整段识别 / 会话（V2）。
 *
 * <pre>
 * GET    /api/speech/asr/settings   当前设置（无行 → 默认值）
 * PUT    /api/speech/asr/settings   整份覆盖（校验失败 400）
 * GET    /api/speech/asr/providers  provider 列表（含设置页字段元数据）
 * GET    /api/speech/asr/models     模型能力表（按 provider + 地域过滤）
 * GET    /api/speech/asr/status     会话状态
 * POST   /api/speech/asr/test       用内置静音 WAV 探活
 * POST   /api/speech/asr/vad        批量帧 VAD（body {"pcm":[float...]}）
 * POST   /api/speech/asr/recognize  multipart 整段 WAV 识别
 * POST   /api/speech/asr/sessions   开启会话（已有 → 409）
 * POST   /api/speech/asr/sessions/stop  结束会话（幂等）
 * DELETE /api/speech/asr/sessions   取消会话（幂等）
 * </pre>
 *
 * <p><b>V2 与上游的三处必然差异</b>（都是实现方式，不是语义）：
 * <ol>
 *   <li>VAD 的概率来自能量门控（{@link EnergyVad}），不是 Silero 神经网络——本项目不加
 *       ONNX Runtime 依赖；事件名、帧率、事件语义与 {@code vad_silence_ms} 行为不变；</li>
 *   <li>{@code /vad} 一次收<b>多帧</b>：上游是进程内 IPC（每 512 样本调一次，30 秒录音
 *       940 次），换 HTTP 后照抄会多出 2~3 秒往返开销。接口形状不变（仍是 {@code pcm}
 *       数组），前端每 ~512ms 传一次（16 帧）；</li>
 *   <li>事件从「推送」改为「同步返回」：浏览器侧没有 {@code asr://*} 事件通道，
 *       所以一批里发生的所有事件直接放在响应体的 {@code events} 数组里。</li>
 * </ol>
 *
 * <p>仍不提供 {@code /streaming/**} 与 {@code /ptt-global}（属 V3/V4）。
 */
@RestController
@RequestMapping("/api/speech/asr")
public class AsrController {

    /** 探活用静音 WAV：44 字节头 + 16000 个 0（16kHz / 单声道 / 16bit / 1 秒）。 */
    private static final byte[] SILENT_WAV = buildSilentWav();

    private final AsrSettingsService settingsService;
    private final AsrRegistry registry;
    private final AsrSessionService sessionService;
    private final EnergyVad energyVad;

    public AsrController(AsrSettingsService settingsService,
                         AsrRegistry registry,
                         AsrSessionService sessionService,
                         EnergyVad energyVad) {
        this.settingsService = settingsService;
        this.registry = registry;
        this.sessionService = sessionService;
        this.energyVad = energyVad;
    }

    /** 当前设置。读取时就修野值（active_provider 已失效 → 回退默认），前端无需自己兜。 */
    @GetMapping("/settings")
    public AsrSettings settings() {
        return settingsService.load();
    }

    /**
     * 整份覆盖设置。
     *
     * <p>回显保存后的对象（含补全的 provider 占位配置），前端不必再 GET 一次就能刷新表单。
     */
    @PutMapping("/settings")
    public AsrSettings saveSettings(@RequestBody(required = false) AsrSettings body) {
        settingsService.save(body);
        return body;
    }

    /** provider 列表：顺序固定 qwen-asr → llama-asr → mock-asr（下拉第一项即默认项）。 */
    @GetMapping("/providers")
    public List<AsrProviderInfo> providers() {
        return registry.listProviderInfo();
    }

    /**
     * 模型能力表。
     *
     * <p>{@code providerId} 缺省用当前设置的 provider，{@code region} 缺省用该 provider
     * 已保存的地域：设置页切地域时立刻重拉清单，但表单还没落库，所以要能显式覆盖。
     */
    @GetMapping("/models")
    public List<AsrModelInfo> models(@RequestParam(name = "providerId", required = false) String providerId,
                                     @RequestParam(name = "region", required = false) String region) {
        AsrSettings settings = settingsService.load();
        String effectiveProvider = isBlank(providerId) ? settings.getActiveProvider() : providerId.trim();
        String effectiveRegion = region;
        if (isBlank(effectiveRegion)) {
            AsrProviderConfig config = settings.getProviderConfigs().get(effectiveProvider);
            effectiveRegion = config == null ? null : config.getRegion();
        }
        return registry.models(effectiveProvider, effectiveRegion);
    }

    /**
     * 会话状态。
     *
     * <p>键名全 snake_case：其中 {@code vad_loaded} / {@code ptt_global_ok} 是上游
     * {@code AsrStatus} 的契约字段，其余是本项目扩展（见
     * {@link AsrSessionService.AsrStatus}）。无会话时 {@code active_source} / {@code session_id} 为 null。
     */
    @GetMapping("/status")
    public AsrSessionService.AsrStatus status() {
        return sessionService.status();
    }

    /**
     * 探活：用一段 1 秒静音跑一次 provider 的 {@code recognize}，验证「配好了没有」。
     *
     * <p>它不是「识别测试」——静音本就识别不出内容，看的是错误码与耗时：
     * 缺 key 会立刻抛 {@link cn.edu.zjut.interview.speech.asr.AsrException}，
     * 由 {@link SpeechExceptionHandler} 转成前端认识的结构，这里不吞不转。
     */
    @PostMapping("/test")
    public TestView test(@RequestBody(required = false) TestRequest request) {
        AsrSettings settings = settingsService.load();
        String providerId = request == null || isBlank(request.providerId())
                ? settings.getActiveProvider()
                : request.providerId().trim();
        AsrProvider provider = registry.require(providerId);
        long startedAt = System.nanoTime();
        AsrResult result = provider.recognize(SILENT_WAV, AsrOptions.empty());
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return new TestView(true, result.providerId(), latencyMs, result.text());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 批量帧 VAD：一次传多帧 PCM（float，[-1,1]），返回本批发生的事件。
     *
     * <p>{@code pcm.length % 512 != 0} 时只处理完整的帧，尾部丢弃（见
     * {@link EnergyVad#process}）——不补零，补零会被判成静音帧、提前 sealed。
     *
     * <p>{@code speech_prob} 取本批<b>最后一帧</b>的概率：与上游
     * {@code VadProcessResult.speech_prob} 同义，前端用它画电平/判断「现在有没有在说」。
     */
    @PostMapping("/vad")
    public VadProcessView vad(@RequestBody(required = false) VadRequest request) {
        AsrSettings settings = settingsService.load();
        float[] pcm = request == null || request.pcm() == null ? new float[0] : request.pcm();
        EnergyVad.VadBatchResult result =
                energyVad.process(pcm, settings.getVadSilenceMs(), settings.isVadDebugLog());

        List<VadEventView> events = new ArrayList<>(result.events().size());
        for (VadSegmenter.SegmentEvent event : result.events()) {
            events.add(VadEventView.from(event));
        }
        // 空批（不足一帧）没有「最后一帧」，给 0 而不是 null：前端把它当数值用
        float speechProb = result.speechProbs().isEmpty()
                ? 0f
                : result.speechProbs().get(result.speechProbs().size() - 1);
        return new VadProcessView(speechProb, result.frame(), events);
    }

    /**
     * 整段 WAV 识别。
     *
     * <p>两条硬要求：
     * <ol>
     *   <li>{@code wav} 字段为空 / 不是合法 WAV → 400 {@code ASR_INVALID_AUDIO}，
     *       <b>绝不把空字节传给 provider</b>（provider 会拿它去发一次 HTTP 请求，用户看到的是
     *       「识别服务报错」而不是「录音是空的」）；</li>
     *   <li>先 {@link WavCodec#decode} 校验格式，再把<b>原始 wav 字节</b>交给 provider——
     *       provider 要的是可上传的音频字节，不是 float 采样，转成 float 反而要再编码一次。</li>
     * </ol>
     */
    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AsrResult recognize(@RequestParam(name = "wav", required = false) MultipartFile wav,
                               @RequestParam(name = "providerId", required = false) String providerId,
                               @RequestParam(name = "languageHint", required = false) String languageHint)
            throws IOException {
        if (wav == null || wav.isEmpty()) {
            throw new AsrException(AsrErrorKind.INVALID_AUDIO, HttpStatus.BAD_REQUEST,
                    "上传的 wav 为空");
        }
        byte[] bytes = wav.getBytes();
        // 校验在前：格式不对就不该走到 provider（一次云端调用是有成本的）
        WavCodec.decode(bytes);

        AsrSettings settings = settingsService.load();
        String effectiveProvider = isBlank(providerId) ? settings.getActiveProvider() : providerId.trim();
        AsrProvider provider = registry.require(effectiveProvider);
        return provider.recognize(bytes, new AsrOptions(
                isBlank(languageHint) ? null : languageHint.trim(), List.of()));
    }

    /**
     * 开启会话。
     *
     * <p>成功时清 VAD 状态与噪声底：上一轮的 {@code speech_active} 若留着，新一轮第一帧
     * 就不会触发 {@code speech_started}，前端等不到「开始录音」事件。
     */
    @PostMapping("/sessions")
    public SessionView startSession(@RequestBody(required = false) SessionStartRequest request) {
        AsrSessionService.ActiveSession session = sessionService.start(request == null ? null : request.source());
        energyVad.reset();
        return SessionView.of(session);
    }

    /**
     * 结束会话；无会话时返回 200 + 空体（幂等）。
     *
     * <p>上游传 {@code source}，这里忽略：服务端只有一个会话，没有「按来源结束」的歧义。
     */
    @PostMapping("/sessions/stop")
    public ResponseEntity<SessionView> stopSession(@RequestBody(required = false) SessionStartRequest request) {
        return sessionService.stop().map(SessionView::of).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok().build());
    }

    /** 取消会话：不产出识别结果（切页面/离开时的清理）；幂等。 */
    @DeleteMapping("/sessions")
    public ResponseEntity<Void> cancelSession() {
        sessionService.cancel();
        return ResponseEntity.ok().build();
    }

    /**
     * 构造 44 字节 WAV 头 + 1 秒静音。
     *
     * <p>只用于探活，不用于识别：真音频由前端（OfflineAudioContext 重采样到 16kHz 单声道）
     * 或后续批次的 VAD 提供，后端不打算做音频编解码。
     */
    private static byte[] buildSilentWav() {
        int sampleRate = 16_000;
        int dataSize = sampleRate * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(16);            // fmt 块大小
        buffer.putShort((short) 1);   // PCM
        buffer.putShort((short) 1);   // 单声道
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * 2); // 字节率 = 采样率 × 声道 × 位深/8
        buffer.putShort((short) 2);   // 块对齐
        buffer.putShort((short) 16);  // 位深
        buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);
        // 剩余字节保持 0 = 静音
        return buffer.array();
    }

    /** 探活请求体：不传 providerId 就用当前设置里的 provider。 */
    public record TestRequest(String providerId) {
    }

    /**
     * {@code /vad} 请求体。
     *
     * <p>形状与上游 {@code asr_vad_process_chunk({ pcm })} 一致，只是允许一次传多帧
     * （差异 B）：字段名不变，前端不必为「批量」改结构，只改缓冲区大小。
     */
    public record VadRequest(float[] pcm) {
    }

    /**
     * {@code /vad} 响应体。
     *
     * @param speechProb 本批最后一帧的语音概率（上游 {@code VadProcessResult.speech_prob}）
     * @param frame      处理完本批后的帧号（前端可据此对齐样本位置：样本 = frame × 512）
     * @param events     本批发生的全部事件，可能为空数组
     */
    public record VadProcessView(
            @JsonProperty("speech_prob") float speechProb,
            @JsonProperty("frame") long frame,
            @JsonProperty("events") List<VadEventView> events) {
    }

    /** {@code /sessions} 请求体；{@code /sessions/stop} 也收它但对 source 不做处理。 */
    public record SessionStartRequest(String source) {
    }

    /**
     * 会话视图：键名 snake_case，与 {@code AsrStatus} 里同名键保持一致
     * （前端拿一个 {@code session_id} 变量在两处比对，键名不一致就永远不相等）。
     */
    public record SessionView(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("source") String source,
            @JsonProperty("started_at") Instant startedAt) {

        static SessionView of(AsrSessionService.ActiveSession session) {
            return new SessionView(session.sessionId(), session.source(), session.startedAt());
        }
    }

    /**
     * 探活结果。
     *
     * @param ok        是否成功（失败时走异常处理，不会返回 false——{@code ok} 只为前端少写一个分支）
     * @param providerId 实际用到的 provider
     * @param latencyMs 调用耗时（毫秒）：前端用它判断「是不是卡住了」
     * @param message   识别出的文本（mock 下是那句固定文案）
     */
    public record TestView(boolean ok, String providerId, long latencyMs, String message) {
    }
}

package cn.edu.zjut.interview.api;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 语音识别接口（语音线 V1）：设置读写 + 元数据 + 会话状态 + 探活。
 *
 * <pre>
 * GET  /api/speech/asr/settings   当前设置（无行 → 默认值）
 * PUT  /api/speech/asr/settings   整份覆盖（校验失败 400）
 * GET  /api/speech/asr/providers  provider 列表（含设置页字段元数据）
 * GET  /api/speech/asr/models     模型能力表（按 provider + 地域过滤）
 * GET  /api/speech/asr/status     会话状态
 * POST /api/speech/asr/test       用内置静音 WAV 探活
 * </pre>
 *
 * <p><b>V1 刻意不提供</b> {@code /sessions}、{@code /recognize}、{@code /vad}、
 * {@code /streaming/**}、{@code /ptt-global}：这些属于 V2/V3。不写空方法占位——
 * 让 Spring 自然 404，前端调错时能立刻看到「接口不存在」，而不是一个 200 的空壳
 * 让人以为功能已经上线。
 */
@RestController
@RequestMapping("/api/speech/asr")
public class AsrController {

    /** 探活用静音 WAV：44 字节头 + 16000 个 0（16kHz / 单声道 / 16bit / 1 秒）。 */
    private static final byte[] SILENT_WAV = buildSilentWav();

    private final AsrSettingsService settingsService;
    private final AsrRegistry registry;
    private final AsrSessionService sessionService;

    public AsrController(AsrSettingsService settingsService,
                         AsrRegistry registry,
                         AsrSessionService sessionService) {
        this.settingsService = settingsService;
        this.registry = registry;
        this.sessionService = sessionService;
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

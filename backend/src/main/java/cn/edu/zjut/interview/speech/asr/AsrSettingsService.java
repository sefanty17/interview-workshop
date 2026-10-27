package cn.edu.zjut.interview.speech.asr;

import cn.edu.zjut.interview.service.SpeechSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASR 设置的领域服务：读（含野值修复）、校验、整份保存。
 *
 * <p>为什么不把这些塞进 {@link SpeechSettingService}：那是「JSON 存取」的通用能力，
 * 将来 TTS 也用它；而「send_mode 只能是两个值」「active_provider 必须在注册表里」
 * 是 ASR 自己的规矩，写进通用层会让它变成什么都得知道一点的大杂烩。
 *
 * <p>与原版 {@code settings.rs::load} 一致：读出来的 {@code active_provider} 若不在
 * 注册表（旧版本删过 provider），回退 {@link AsrSettings#DEFAULT_PROVIDER} 并 warn，
 * 而不是把错误抛给前端——设置页打不开比「用了默认 provider」严重得多。
 */
@Service
public class AsrSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AsrSettingsService.class);

    /** 静音计时下限：低于 100ms 会把换气声切成两段。 */
    private static final int VAD_SILENCE_MIN_MS = 100;
    /** 静音计时上限：再长用户会以为程序卡住了。 */
    private static final int VAD_SILENCE_MAX_MS = 5000;
    private static final int ENERGY_WARMUP_MIN_MS = 0;
    private static final int ENERGY_WARMUP_MAX_MS = 2000;

    private final SpeechSettingService store;
    private final AsrRegistry registry;

    public AsrSettingsService(SpeechSettingService store, AsrRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** 当前设置：没有行 / payload 损坏 → 默认值；active_provider 已失效 → 回退默认。 */
    public AsrSettings load() {
        AsrSettings settings = store.read(SpeechSettingService.KIND_ASR, AsrSettings.class,
                AsrSettings.defaults());
        if (!registry.contains(settings.getActiveProvider())) {
            log.warn("[ASR] active_provider '{}' 不在注册表中，回退 {}", settings.getActiveProvider(),
                    AsrSettings.DEFAULT_PROVIDER);
            settings.setActiveProvider(AsrSettings.DEFAULT_PROVIDER);
        }
        return settings;
    }

    /** 整个覆盖保存：校验通过才落库，不会出现「一半生效」。 */
    @Transactional
    public void save(AsrSettings settings) {
        validate(settings);
        store.write(SpeechSettingService.KIND_ASR, settings);
    }

    /**
     * 校验请求体。
     *
     * <p>未知 provider 用 400（{@link AsrException} 的分类仍是
     * {@link AsrErrorKind#PROVIDER_NOT_FOUND}，前端据此查文案）：请求体里写错 id 是
     * 参数错误，不是「请求的资源不存在」——404 会让前端以为是地址写错了。
     *
     * <p>同时补齐缺失的 provider 占位配置：前端只传改动的几个字段时，其余 provider
     * 也得有对象，否则设置页切过去会拿到 undefined。
     *
     * <p>为什么全部抛 {@link AsrErrorKind#INVALID_SETTINGS} 而不是
     * {@code ResponseStatusException}：后者经 Spring 默认处理器出去只有
     * {@code timestamp/status/error/path} 五个键，没有 {@code code}，且
     * {@code server.error.include-message=never} 把 reason 也吞掉了——前端拿不到
     * 「哪个字段越界」这件事，只能显示 "HTTP 400"。未知 provider 那条仍用
     * {@link AsrErrorKind#PROVIDER_NOT_FOUND}（前端已有对应文案）。
     */
    public void validate(AsrSettings settings) {
        if (settings == null) {
            throw new AsrException(AsrErrorKind.INVALID_SETTINGS, HttpStatus.BAD_REQUEST,
                    "语音设置不能为空");
        }
        String providerId = settings.getActiveProvider();
        if (providerId == null || providerId.isBlank() || !registry.contains(providerId)) {
            throw new AsrException(AsrErrorKind.PROVIDER_NOT_FOUND, HttpStatus.BAD_REQUEST,
                    "未知的语音识别 provider：" + providerId);
        }
        if (!AsrSettings.SEND_MODES.contains(settings.getSendMode())) {
            throw new AsrException(AsrErrorKind.INVALID_SETTINGS, HttpStatus.BAD_REQUEST,
                    "send_mode 只能是 " + String.join(" 或 ", AsrSettings.SEND_MODES));
        }
        if (settings.getVadSilenceMs() < VAD_SILENCE_MIN_MS || settings.getVadSilenceMs() > VAD_SILENCE_MAX_MS) {
            throw new AsrException(AsrErrorKind.INVALID_SETTINGS, HttpStatus.BAD_REQUEST,
                    "vad_silence_ms 必须在 " + VAD_SILENCE_MIN_MS + "–" + VAD_SILENCE_MAX_MS + " 之间");
        }
        if (settings.getEnergyWarmupMs() < ENERGY_WARMUP_MIN_MS
                || settings.getEnergyWarmupMs() > ENERGY_WARMUP_MAX_MS) {
            throw new AsrException(AsrErrorKind.INVALID_SETTINGS, HttpStatus.BAD_REQUEST,
                    "energy_warmup_ms 必须在 " + ENERGY_WARMUP_MIN_MS + "–" + ENERGY_WARMUP_MAX_MS + " 之间");
        }
        settings.setProviderConfigs(withPlaceholders(settings.getProviderConfigs()));
    }

    /** 按注册表把缺失的 provider 配置补成空对象（原有的不动）。 */
    private static Map<String, AsrProviderConfig> withPlaceholders(Map<String, AsrProviderConfig> configs) {
        Map<String, AsrProviderConfig> merged = new LinkedHashMap<>();
        if (configs != null) {
            configs.forEach((id, config) ->
                    merged.put(id, config == null ? new AsrProviderConfig() : config));
        }
        for (String providerId : AsrRegistry.PROVIDER_ORDER) {
            merged.putIfAbsent(providerId, new AsrProviderConfig());
        }
        return merged;
    }
}

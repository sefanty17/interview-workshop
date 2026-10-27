package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.service.SpeechSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TTS 设置的领域服务：读、校验、整份保存。与 {@code AsrSettingsService} 同一分工——
 * {@link SpeechSettingService} 只管 JSON 存取，这里管 TTS 自己的规矩。
 *
 * <p>与 ASR 的一处不同：ASR 读到「已不存在的 provider」会静默回退默认值，
 * 因为 provider 列表是可变的（将来可能删掉某个 provider）。TTS 的 13 个引擎类型是
 * 前端写死的下拉项，后端列表只会增加不会减少，读到未知值说明数据库里存的是别的
 * 程序/人工改过的内容，那属于脏数据，值得 warn 一次再回退，而不是无声无息地改掉用户选择。
 */
@Service
public class TtsSettingsService {

    private static final Logger log = LoggerFactory.getLogger(TtsSettingsService.class);

    private final SpeechSettingService store;

    public TtsSettingsService(SpeechSettingService store) {
        this.store = store;
    }

    /** 当前设置：没有行 / payload 损坏 → 默认值；ttsType 不在 13 个引擎里 → 回退默认并 warn。 */
    public TtsSettings load() {
        TtsSettings settings = store.read(SpeechSettingService.KIND_TTS, TtsSettings.class,
                TtsSettings.defaults());
        if (!TtsEngineCatalog.isKnown(settings.getTtsType())) {
            log.warn("[TTS] ttsType '{}' 不在引擎列表中，回退 {}", settings.getTtsType(), TtsSettings.DEFAULT_TYPE);
            settings.setTtsType(TtsSettings.DEFAULT_TYPE);
        }
        return settings;
    }

    /** 整个覆盖保存：校验通过才落库，不会出现「一半生效」。 */
    @Transactional
    public void save(TtsSettings settings) {
        validate(settings);
        store.write(SpeechSettingService.KIND_TTS, settings);
    }

    /**
     * 校验请求体。
     *
     * <p><b>只拦「未知类型」</b>，不拦「暂未接入的类型」：前端 {@code ttsType} 的默认值就是
     * {@code edge-tts}（还没接入），如果在保存时按「已实现」校验，用户什么都不改、点一下保存
     * 就 400。写错 id 才是参数错误；能不能合成是调用期的事，由
     * {@link TtsService#synthesize} 抛 {@code TTS_ENGINE_UNSUPPORTED}。
     *
     * <p>用 400 而不是 404：请求体里的枚举值写错属于参数错误，404 会让前端以为接口地址变了。
     */
    public void validate(TtsSettings settings) {
        if (settings == null) {
            throw new TtsException(TtsErrorKind.NOT_CONFIGURED, HttpStatus.BAD_REQUEST, "TTS 设置不能为空");
        }
        if (!TtsEngineCatalog.isKnown(settings.getTtsType())) {
            String known = String.join(", ",
                    TtsEngineCatalog.ENGINES.stream().map(TtsEngineCatalog.Entry::type).toList());
            throw TtsException.unsupported("未知的 TTS 引擎：" + settings.getTtsType() + "（可选：" + known + "）");
        }
    }
}

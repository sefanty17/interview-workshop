package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.Bv2Adapter;
import cn.edu.zjut.interview.speech.tts.adapters.CosyvoiceAdapter;
import cn.edu.zjut.interview.speech.tts.adapters.FishS2Adapter;
import cn.edu.zjut.interview.speech.tts.adapters.GsvAdapter;
import cn.edu.zjut.interview.speech.tts.adapters.Sbv2Adapter;
import cn.edu.zjut.interview.speech.tts.adapters.Sbv2ApiAdapter;
import cn.edu.zjut.interview.speech.tts.adapters.VitsAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TTS 的统一出口：对应上游 {@code provider.rs::TtsProvider}。
 *
 * <p>三件事：按 {@code ttsType} 路由到适配器、维护「连续失败 → 自动禁用」状态、
 * 对外暴露引擎清单。
 *
 * <p><b>适配器每次现造</b>（{@link #createAdapter}）：对象本身只有一个 settings 引用，
 * 构造成本可以忽略；缓存实例反而会让「改了服务地址 / 密钥」必须重启才生效，
 * 而设置页的语义是保存即生效。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    /**
     * 连续失败多少次才禁用 TTS，取值同上游 {@code provider.rs:44}（{@code MAX_CONSECUTIVE_FAILURES}）。
     *
     * <p>为什么不是「失败一次就关」：个别句子会因为<b>文本本身</b>失败（纯标点、
     * 推理后端不支持的张量形状等），而下一句完全正常。一次失败就把整个语音服务关掉，
     * 用户会以为程序坏了；只有连续失败才说明后端真的不可用（服务没启动、URL 配错）。
     */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 已接入 8 个引擎。V5c 往这里加，{@code /engines} 的 supported 自动跟着变。 */
    public static final Set<String> SUPPORTED_TYPES = Set.of(
            Sbv2Adapter.TYPE, Sbv2ApiAdapter.TYPE, Bv2Adapter.TYPE, VitsAdapter.TYPE,
            GsvAdapter.TYPE_GSV, GsvAdapter.TYPE_GPT_SOVITS, FishS2Adapter.TYPE, CosyvoiceAdapter.TYPE);

    private final TtsSettingsService settingsService;

    /** 是否启用。内存态，不落库：与上游的 {@code AtomicBool} 一致，重启即恢复。 */
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /** 连续失败计数，任意一次成功即归零（{@code provider.rs:250}）。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public TtsService(TtsSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** 按当前设置的引擎合成。 */
    public byte[] synthesize(String text, String emo) {
        TtsSettings settings = settingsService.load();
        return synthesizeWith(settings, settings.getTtsType(), text, emo);
    }

    /**
     * 用指定引擎合成（`/test` 端点的「切到某引擎试一下」用）。
     *
     * <p>不动已保存的设置：设置页的「测试」按钮要在<b>落库之前</b>验证一串参数能不能用，
     * 改设置再试会把用户的表单冲掉。
     */
    public byte[] synthesizeAs(String type, String text, String emo) {
        TtsSettings settings = settingsService.load();
        return synthesizeWith(settings, type, text, emo);
    }

    private byte[] synthesizeWith(TtsSettings settings, String type, String text, String emo) {
        if (!enabled.get()) {
            throw new TtsException(TtsErrorKind.DISABLED, HttpStatus.SERVICE_UNAVAILABLE,
                    "TTS 已因连续失败 " + MAX_CONSECUTIVE_FAILURES + " 次被禁用，请先调用 reactivate()");
        }
        if (text == null || text.isBlank()) {
            // 上游对空文本是「跳过、算成功」（provider.rs:243），这里没有「跳过」这个返回值形态，
            // 所以按缺参数报错；不抛的话调用方会拿到 0 字节音频并在播放时静默失败
            throw TtsException.notConfigured("合成文本为空");
        }

        // 适配器构造阶段就会抛「未接入 / 缺地址」，这些错误不计入失败数：
        // 配置错误不是「后端挂了」，不该把整个 TTS 关掉
        TtsAdapter adapter = createAdapter(type, settings);

        try {
            byte[] audio = adapter.generate(text, emo);
            consecutiveFailures.set(0);
            return audio;
        } catch (RuntimeException e) {
            if (isConfigError(e)) {
                // 「没填地址 / 引擎没接」是用户表单的问题，重试一万次也一样：不记账，
                // 否则用户连点三次测试按钮就会把整个 TTS 关掉，还得重启服务才能恢复
                throw e;
            }
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                enabled.set(false);
                log.warn("[TTS] 连续失败 {}/{} 次，已禁用 TTS（成功一次或 reactivate() 可恢复）：{}",
                        failures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            } else {
                log.warn("[TTS] 合成失败（{}/{}），保持启用继续尝试下一句：{}",
                        failures, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            }
            throw e;
        }
    }

    private static boolean isConfigError(RuntimeException e) {
        return e instanceof TtsException tts
                && (tts.kind() == TtsErrorKind.NOT_CONFIGURED || tts.kind() == TtsErrorKind.ENGINE_UNSUPPORTED);
    }

    /** 禁用后手动恢复（上游的 {@code reactivate_tts}）：清计数，下次合成重新从 0 开始。 */
    public void reactivate() {
        enabled.set(true);
        consecutiveFailures.set(0);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /** 13 个引擎的元信息，顺序与设置页下拉一致（见 {@link TtsEngineCatalog#ENGINES}）。 */
    public List<TtsEngineInfo> engines() {
        List<TtsEngineInfo> result = new ArrayList<>(TtsEngineCatalog.ENGINES.size());
        for (TtsEngineCatalog.Entry entry : TtsEngineCatalog.ENGINES) {
            boolean supported = SUPPORTED_TYPES.contains(entry.type());
            String note = supported
                    ? ""
                    // 「确定不做」的引擎带自己的理由；「还没轮到做」的统一说明，免得 V5b 落地后回来改文案
                    : (entry.permanentNote() != null ? entry.permanentNote() : TtsEngineCatalog.PENDING_NOTE);
            result.add(new TtsEngineInfo(entry.type(), entry.displayName(), supported, note));
        }
        return result;
    }

    /**
     * 按类型造适配器。
     *
     * @throws TtsException 类型不认识或尚未接入（{@code TTS_ENGINE_UNSUPPORTED}）
     */
    private TtsAdapter createAdapter(String type, TtsSettings settings) {
        if (type == null || type.isBlank()) {
            throw TtsException.unsupported("未指定 TTS 引擎类型");
        }
        return switch (type) {
            case Sbv2Adapter.TYPE -> new Sbv2Adapter(settings);
            case Sbv2ApiAdapter.TYPE -> new Sbv2ApiAdapter(settings);
            case Bv2Adapter.TYPE -> new Bv2Adapter(settings);
            case VitsAdapter.TYPE -> new VitsAdapter(settings);
            case GsvAdapter.TYPE_GSV -> new GsvAdapter(settings, GsvAdapter.TYPE_GSV);
            case GsvAdapter.TYPE_GPT_SOVITS -> new GsvAdapter(settings, GsvAdapter.TYPE_GPT_SOVITS);
            case FishS2Adapter.TYPE -> new FishS2Adapter(settings);
            case CosyvoiceAdapter.TYPE -> new CosyvoiceAdapter(settings);
            default -> {
                TtsEngineCatalog.Entry entry = TtsEngineCatalog.require(type);
                throw TtsException.unsupported("引擎 " + entry.type() + "（" + entry.displayName()
                        + "）尚未接入：" + (entry.permanentNote() != null
                        ? entry.permanentNote() : TtsEngineCatalog.PENDING_NOTE));
            }
        };
    }

    /** 引擎元信息。字段名 camelCase，与前端 {@code value/label} 对齐后可直接渲染下拉。 */
    public record TtsEngineInfo(String type, String displayName, boolean supported, String note) {
    }
}

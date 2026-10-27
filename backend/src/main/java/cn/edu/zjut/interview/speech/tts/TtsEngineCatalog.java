package cn.edu.zjut.interview.speech.tts;

import java.util.List;

/**
 * 13 个引擎的静态目录：类型、显示名、以及「为什么不能用」的一句人话。
 *
 * <p>数据逐字取自前端 {@code SettingsTts.vue} 的 {@code ENGINE_OPTIONS}（第 708-774 行）：
 * 类型是 {@code value}，显示名是 {@code label}。<b>顺序也照抄</b>——它是设置页下拉的顺序，
 * 后端 {@code /engines} 若排成别的顺序，前端按索引回填选中项就会错位。
 *
 * <p>为什么单开一个类而不是塞进 {@link TtsService}：两张表要被两处用——
 * {@code TtsService#engines()} 要 displayName，{@code TtsSettingsService#validate}
 * 要「这个 type 认不认识」。放在一起才能保证「/engines 里列出来的」与
 * 「PUT 时接受的」是同一份名单；分成两份迟早会漂移。
 *
 * <p>{@link Entry#permanentNote()} 只给<strong>本项目确定不做</strong>的引擎写死理由；
 * 「还没轮到做」的引擎不写（见 {@link #PENDING_NOTE}），
 * 否则 V5b/V5c 落地后还要回来删这批文案。
 */
public final class TtsEngineCatalog {

    /** 尚未实现的引擎统一说明（V5b / V5c 会逐个替换成真实实现）。 */
    public static final String PENDING_NOTE = "尚未接入（计划在后续批次实现）";

    /** 一个引擎的静态元信息。 */
    public record Entry(String type, String displayName, String permanentNote) {
        /** 没有「永久不可用」理由的引擎（要么已实现，要么排期在后面的批次）。 */
        static Entry of(String type, String displayName) {
            return new Entry(type, displayName, null);
        }

        /** 本项目确定不做、且理由不会随批次消失的引擎。 */
        static Entry of(String type, String displayName, String permanentNote) {
            return new Entry(type, displayName, permanentNote);
        }
    }

    public static final List<Entry> ENGINES = List.of(
            Entry.of("edge-tts", "Edge TTS",
                    "Edge 在线语音需要 WebSocket 鉴权，尚未接入；请改用 OpenTTS 等 HTTP 引擎"),
            Entry.of("gpt-sovits", "GPT-SoVITS"),
            Entry.of("sbv2", "Style-Bert-VITS2"),
            Entry.of("sbv2api", "SBV2 API"),
            Entry.of("cosyvoice", "CosyVoice"),
            Entry.of("indextts2", "IndexTTS2"),
            Entry.of("fish-speech", "Fish-Speech / Fish S2"),
            Entry.of("opentts", "OpenTTS"),
            Entry.of("vits", "VITS / Simple-VITS"),
            Entry.of("aivis", "Aivis"),
            Entry.of("bv2", "Bert-VITS2"),
            Entry.of("gsv", "GSV"),
            Entry.of("local-sbv2", "本地 SBV2",
                    "本地 SBV2 模型需要桌面端运行时，本项目不支持；请用远端 SBV2 服务"));

    private TtsEngineCatalog() {
    }

    /** 类型是否在这 13 个之内（PUT 校验用：写错 id 才是 400，暂未实现不算写错）。 */
    public static boolean isKnown(String type) {
        return ENGINES.stream().anyMatch(entry -> entry.type().equals(type));
    }

    public static Entry require(String type) {
        return ENGINES.stream()
                .filter(entry -> entry.type().equals(type))
                .findFirst()
                .orElseThrow(() -> TtsException.unsupported("未知的 TTS 引擎：" + type));
    }
}

package cn.edu.zjut.interview.config;

import cn.edu.zjut.interview.domain.UserSetting;
import cn.edu.zjut.interview.repository.UserSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设置项注册表 + 读写 + 校验，对应原版
 * {@code config/tree.rs::build_config_tree} 与 {@code api/settings.rs::save_settings}。
 *
 * <p>三层职责：
 * <ol>
 *   <li><b>注册表</b>（{@link #REGISTRY}）：17 个 key 的类型/默认值/范围/文案，默认值只写这一份；</li>
 *   <li>{@link #buildTree()}：注册表 + {@code user_settings} 覆盖值 → 前端要的配置树
 *       （{@link #TREE_SPEC} 决定「哪个 key 挂在哪个子分类」，只建有内容的子类，不建空壳）；</li>
 *   <li>{@link #saveAll(Map)}：逐项校验（key 存在 / 类型可解析 / 在 min..max 内 / options 合法），
 *       全通过才落库，报错文案走原版风格「LLM 请求超时必须在 5–300 之间」。</li>
 * </ol>
 *
 * <p>读取侧（{@link #getInt} 等）也做一次「存进去的值不合法就回落默认值」的保护，
 * 对齐原版 {@code app_config.rs} 的 {@code get_u32_in_range}：手工改库改坏了不该让对话崩。
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** {@code config_value} 是 VARCHAR(2048)，超长的值提前拦下，别留给数据库报 500。 */
    private static final int MAX_VALUE_LENGTH = 2048;

    /** A3：llm.model / temperature / top_p 三处共用的「简化实现」说明。 */
    private static final String SIMPLIFIED_PROVIDER_NOTE =
            "简化实现：原版运行时改读 provider 级配置（provider_config.rs:112），全局 key 仅作默认值；"
                    + "本项目未做 provider 表";

    /** A2：display 两项共用的「本项目扩展」说明。 */
    private static final String FONT_EXTENSION_NOTE =
            "本项目扩展：原版字号属投屏模块 cast.dialog_font_size，本项目不迁投屏故自建";

    /**
     * 配置树的两层结构（照原版 {@code Category → Subcategory}，分类名用原版中文名）。
     *
     * <p>原版 8 个顶层分类里，翻译配置 / TTS 配置 / 创意工坊 / 日志配置 / 主动对话配置 / 好感度
     * 对应模块尚未迁移，这里不建空壳。顺序即渲染顺序。
     */
    private static final List<CategorySpec> TREE_SPEC = List.of(
            new CategorySpec("llm", "LLM 配置", List.of(
                    new SubcategorySpec("advanced", "高级选项", List.of(
                            ConfigKey.LLM_MODEL,
                            ConfigKey.LLM_TEMPERATURE,
                            ConfigKey.LLM_TOP_P,
                            ConfigKey.LLM_TIMEOUT_SECS,
                            ConfigKey.LLM_MAX_TOKENS,
                            ConfigKey.LLM_CHAT_PROVIDER_ID,
                            ConfigKey.LLM_ENABLE_THINKING)))),
            new CategorySpec("features", "功能设置", List.of(
                    new SubcategorySpec("chat-enhance", "对话增强", List.of(
                            ConfigKey.FEATURES_ENABLE_TIME_SENSE,
                            ConfigKey.FEATURES_ENABLE_EMOTION_CLASSIFIER,
                            ConfigKey.CHAT_INLINE_ACTION)),
                    new SubcategorySpec("interface", "界面与显示", List.of(
                            ConfigKey.DISPLAY_FONT_FAMILY,
                            ConfigKey.DISPLAY_FONT_SIZE)),
                    new SubcategorySpec("chat-rhythm", "对话节奏（本项目扩展）", List.of(
                            ConfigKey.CHAT_REPLY_COUNT,
                            ConfigKey.CHAT_MERGE_THRESHOLD,
                            ConfigKey.CHAT_MERGE_DELAY_MS,
                            ConfigKey.CHAT_TEXT_SPEED,
                            ConfigKey.CHAT_AUTO_ADVANCE_MS)),
                    new SubcategorySpec("knowledge", "岗位知识（本项目扩展）", List.of(
                            ConfigKey.KNOWLEDGE_INJECT_ENABLED,
                            ConfigKey.KNOWLEDGE_INJECT_TOP_K)))));

    /**
     * 有真实消费点的配置键（改这些键会影响运行行为）。
     * 新增配置项时必须同步维护：要么加进这里，要么 extension=true，
     * 要么 description 里写明「仅存值」——三者必有其一，由 ConfigServiceTest 守卫。
     *
     * <p>消费点明细见 {@code config/package-info.java}。
     */
    static final Set<String> CONSUMED_KEYS = Set.of(
            ConfigKey.LLM_MODEL,            // LlmOptions.from → 请求体 model
            ConfigKey.LLM_TEMPERATURE,      // LlmOptions.from → 请求体 temperature
            ConfigKey.LLM_TOP_P,            // LlmOptions.from → 请求体 top_p
            ConfigKey.LLM_MAX_TOKENS,       // LlmOptions.from → 请求体 max_tokens
            ConfigKey.LLM_TIMEOUT_SECS,     // LlmOptions.from → HttpRequest timeout
            ConfigKey.CHAT_REPLY_COUNT,     // ChatTuning → PromptAssembler
            ConfigKey.CHAT_MERGE_THRESHOLD, // ChatTuning → SentenceSplitter
            ConfigKey.KNOWLEDGE_INJECT_ENABLED, // ChatService → KnowledgeInjector 开关
            ConfigKey.KNOWLEDGE_INJECT_TOP_K    // ChatService → KnowledgeInjector 每轮注入片段数
    );

    /** key → 定义；LinkedHashMap 保住注册顺序（也就是前端渲染顺序）。 */
    private static final Map<String, ConfigSetting> REGISTRY = buildRegistry();

    private final UserSettingRepository repository;

    public ConfigService(UserSettingRepository repository) {
        this.repository = repository;
    }

    /**
     * 17 个设置项的定义。范围值见每行的注释：能对上原版常量的直接引用原版
     * （{@code app_config.rs} 的 {@code MIN_LLM_TIMEOUT_SECS} = 10 / {@code MAX_LLM_TIMEOUT_SECS} = 3600），
     * 对不上的（本项目扩展）在 {@code description} 和 {@code extension} 里标清楚。
     *
     * <p>每个 key 必须是「有消费点（{@link #CONSUMED_KEYS}）/ 本项目扩展 / description 写「仅存值」」
     * 三者之一，否则 {@code ConfigServiceTest} 守卫会失败。
     */
    private static Map<String, ConfigSetting> buildRegistry() {
        List<ConfigSetting> settings = List.of(
                // ===== LLM 配置 =====
                ConfigSetting.stringSetting(ConfigKey.LLM_MODEL, "llm", "模型名", "deepseek-reasoner",
                        "对话使用的模型名（deepseek-reasoner 才有思考链），对应原版 llm.model。"
                                + SIMPLIFIED_PROVIDER_NOTE, false),
                ConfigSetting.doubleSetting(ConfigKey.LLM_TEMPERATURE, "llm", "温度", 1.0, 0.0, 2.0,
                        "采样温度，越高越发散，对应原版 llm.temperature。"
                                + SIMPLIFIED_PROVIDER_NOTE, false),
                ConfigSetting.doubleSetting(ConfigKey.LLM_TOP_P, "llm", "top_p", 1.0, 0.0, 1.0,
                        "核采样阈值，对应原版 llm.top_p。"
                                + SIMPLIFIED_PROVIDER_NOTE, false),
                // 原版 MIN_LLM_TIMEOUT_SECS=10 / MAX_LLM_TIMEOUT_SECS=3600；本项目收窄到 5–300（默认 60，与 yml 一致）
                ConfigSetting.intSetting(ConfigKey.LLM_TIMEOUT_SECS, "llm", "LLM 请求超时", 60, 5, 300,
                        "LLM 请求空闲超时（秒）。原版 10–3600，本项目收窄为 5–300", false),
                // 原版无此 key
                ConfigSetting.intSetting(ConfigKey.LLM_MAX_TOKENS, "llm", "最大输出 token", 800, 64, 8192,
                        "单次回复的最大 token 数（本项目扩展，原版无此 key）", true),
                ConfigSetting.stringSetting(ConfigKey.LLM_CHAT_PROVIDER_ID, "llm", "对话 Provider", "deepseek",
                        "对话用哪个 provider，对应原版 llm.chat_provider_id（仅存值：本项目只有单一 provider）", false),
                ConfigSetting.boolSetting(ConfigKey.LLM_ENABLE_THINKING, "llm", "思考链", false,
                        "是否请求思考链，对应原版 llm.enable_thinking（仅存值：后端暂不使用）", false),

                // ===== 功能设置 =====
                ConfigSetting.boolSetting(ConfigKey.FEATURES_ENABLE_TIME_SENSE, "features", "时间感知", false,
                        "启用时间感知（按上下文时间注入系统提醒）（仅存值：本批未迁该逻辑）", false),
                ConfigSetting.boolSetting(ConfigKey.FEATURES_ENABLE_EMOTION_CLASSIFIER, "features", "情绪分类器", false,
                        "启用情绪分类器（原版走 ONNX 模型，本项目用别名表实现）（仅存值：别名表始终启用）", false),

                // ===== 对话 =====
                // 原版无此 key：原版是前端「回复台词数量」档位，这里搬到后端做硬约束
                ConfigSetting.intSetting(ConfigKey.CHAT_REPLY_COUNT, "chat", "回复台词数量", 3, 1, 6,
                        "每次回复几句台词；默认值 3 时不追加 prompt 提示（本项目扩展）", true),
                // 原版无此 key：前端 settings.mergeThreshold 的同名语义，这里下沉给分句器
                ConfigSetting.intSetting(ConfigKey.CHAT_MERGE_THRESHOLD, "chat", "台词合并阈值", 55, 0, 200,
                        "短于此长度的台词并入下一句，0 = 不合并（本项目扩展）", true),
                ConfigSetting.intSetting(ConfigKey.CHAT_MERGE_DELAY_MS, "chat", "台词合并续打延迟", 200, 0, 2000,
                        "台词合并后的续打延迟（毫秒；本项目扩展；仅存值：消费端在前端）", true),
                ConfigSetting.intSetting(ConfigKey.CHAT_TEXT_SPEED, "chat", "文字显示速度", 80, 20, 200,
                        "打字速度，数值越大越快（对应原版 textSpeed 语义；仅存值：消费端在前端）", true),
                ConfigSetting.intSetting(ConfigKey.CHAT_AUTO_ADVANCE_MS, "chat", "自动推进延迟", 1000, 0, 5000,
                        "自动推进下一句的延迟（毫秒；本项目扩展；仅存值：消费端在前端）", true),
                ConfigSetting.boolSetting(ConfigKey.CHAT_INLINE_ACTION, "chat", "内联动作文本", false,
                        "动作文本与台词同时显示，无需二次点击（本项目扩展；仅存值：消费端在前端）", true),

                // ===== 岗位知识（本项目扩展：RAG）=====
                ConfigSetting.boolSetting(ConfigKey.KNOWLEDGE_INJECT_ENABLED, "knowledge", "岗位知识注入", true,
                        "面试时按玩家本轮提问检索岗位知识库，命中则注入当轮 system（本项目扩展，"
                                + "阈值与融合权重见 RetrievalService）", true),
                ConfigSetting.intSetting(ConfigKey.KNOWLEDGE_INJECT_TOP_K, "knowledge", "每轮注入片段数", 3, 1, 10,
                        "每轮最多注入几个知识片段；片段约 300~900 字，3 个已占 2000 字上下文（本项目扩展）", true),

                // ===== 界面显示 =====
                ConfigSetting.stringSetting(ConfigKey.DISPLAY_FONT_FAMILY, "display", "界面字体", "",
                        "界面字体，空串 = 软件默认。" + FONT_EXTENSION_NOTE, true),
                ConfigSetting.intSetting(ConfigKey.DISPLAY_FONT_SIZE, "display", "字号", 16, 12, 24,
                        "界面字号（px）。" + FONT_EXTENSION_NOTE, true)
        );

        Map<String, ConfigSetting> registry = new LinkedHashMap<>();
        for (ConfigSetting setting : settings) {
            registry.put(setting.key(), setting);
        }
        return Collections.unmodifiableMap(registry);
    }

    // ───────────────────────── 读：配置树 ─────────────────────────

    /**
     * 组装配置树：按 {@link #TREE_SPEC} 把注册表逐项取 DB 覆盖值（没有覆盖或存的值为非法就用默认值），
     * 空子分类 / 空分类一律不输出。
     */
    public ConfigTree buildTree() {
        Map<String, String> stored = loadStored();

        Set<String> placed = new HashSet<>();
        List<ConfigTree.Category> categories = new ArrayList<>(TREE_SPEC.size());
        for (CategorySpec categorySpec : TREE_SPEC) {
            List<ConfigTree.Subcategory> subcategories = new ArrayList<>(categorySpec.subcategories().size());
            for (SubcategorySpec subSpec : categorySpec.subcategories()) {
                List<ConfigSetting> settings = new ArrayList<>(subSpec.keys().size());
                for (String key : subSpec.keys()) {
                    ConfigSetting definition = REGISTRY.get(key);
                    if (definition == null) {
                        log.warn("[ConfigService] 配置树引用了未注册的 key {}，跳过", key);
                        continue;
                    }
                    placed.add(key);
                    settings.add(definition.withValue(resolveValue(definition, stored.get(key))));
                }
                if (settings.isEmpty()) {
                    continue;
                }
                subcategories.add(new ConfigTree.Subcategory(subSpec.id(), subSpec.label(), List.copyOf(settings)));
            }
            if (subcategories.isEmpty()) {
                continue;
            }
            categories.add(new ConfigTree.Category(categorySpec.id(), categorySpec.label(),
                    List.copyOf(subcategories)));
        }

        // 注册表里有、树里没挂的 key 会直接从前端消失，留一条日志便于发现映射漏项
        for (String key : REGISTRY.keySet()) {
            if (!placed.contains(key)) {
                log.warn("[ConfigService] 配置项 {} 没有挂到配置树", key);
            }
        }
        return new ConfigTree(List.copyOf(categories));
    }

    /** 注册表里的全部定义（不含当前值），给测试与后续批次用。 */
    public static List<ConfigSetting> registry() {
        return allSettings();
    }

    /** 同上，包级可见：{@code ConfigServiceTest} 的「每个键都有消费点或标注」守卫遍历用。 */
    static List<ConfigSetting> allSettings() {
        return List.copyOf(REGISTRY.values());
    }

    /** 某个 key 的定义；不存在返回 {@code null}。 */
    public static ConfigSetting definition(String key) {
        return key == null ? null : REGISTRY.get(key);
    }

    // ───────────────────────── 写：整批保存 ─────────────────────────

    /**
     * 逐项校验后整批落库（存在则更新，不存在则插入）。
     *
     * <p>校验不通过抛 {@link IllegalArgumentException}，由
     * {@link cn.edu.zjut.interview.api.SettingsController} 转成 400 + {"error": "..."}；
     * 校验全部通过才写库，不会出现「一半生效一半报错」。
     *
     * @param values key → 字符串值，同原版 {@code BTreeMap<String,String>}
     */
    @Transactional
    public void saveAll(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("配置项不能为空");
        }
        // 1. 先全量校验
        for (Map.Entry<String, String> entry : values.entrySet()) {
            validate(entry.getKey(), entry.getValue());
        }
        // 2. 再落库（逐 key upsert）
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            UserSetting row = repository.findById(key).orElseGet(() -> new UserSetting(key, value, now));
            row.setConfigValue(value);
            row.setUpdatedAt(now);
            repository.save(row);
        }
        log.info("[ConfigService] 保存设置 {} 项：{}", values.size(), values.keySet());
    }

    /** 校验单个 key/value；不合法抛 {@link IllegalArgumentException}。 */
    public static void validate(String key, String value) {
        ConfigSetting definition = REGISTRY.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("未知配置项：" + key);
        }
        validateValue(definition, value);
    }

    /** 按「类型 + 范围 + options」校验一个值（注册表外的定义也能用，测试与后续批次共用）。 */
    static void validateValue(ConfigSetting definition, String value) {
        String raw = value == null ? "" : value.strip();
        if (raw.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(definition.label() + "长度不能超过 " + MAX_VALUE_LENGTH + " 个字符");
        }
        switch (definition.type()) {
            // number：整数项仍走 requireLong（小数直接被拒），小数项走 requireDouble
            case ConfigSetting.TYPE_NUMBER -> {
                boolean integral = definition.integral();
                double parsed = integral ? requireLong(definition, raw) : requireDouble(definition, raw);
                checkRange(definition, parsed, integral);
            }
            case ConfigSetting.TYPE_BOOL -> {
                if (!"true".equals(raw) && !"false".equals(raw)) {
                    throw new IllegalArgumentException(definition.label() + "只能填 true 或 false");
                }
            }
            case ConfigSetting.TYPE_TEXT -> {
                // 带 options 的 text 项按枚举校验；普通文本只受长度约束
                if (definition.options() != null && !definition.options().isEmpty()
                        && !definition.options().contains(raw)) {
                    throw new IllegalArgumentException(definition.label() + "必须是以下之一："
                            + String.join("、", definition.options()));
                }
            }
            default -> {
                // 未知类型：不做内容校验，保持宽松（原版只有三个值，正常走不到这里）
            }
        }
    }

    /** 值是否合法（读取侧回落默认值用，不抛异常）。 */
    public static boolean isValid(ConfigSetting definition, String value) {
        try {
            validateValue(definition, value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ───────────────────────── 读：单个值 ─────────────────────────

    /** 原始字符串值（无行 / 值非法 → 默认值）。 */
    public String get(String key) {
        ConfigSetting definition = requireDefinition(key);
        return resolveValue(definition, readStored(key));
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public double getDouble(String key) {
        return Double.parseDouble(get(key));
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key));
    }

    /** 默认值（不管 DB 里存了什么）。 */
    public String defaultValue(String key) {
        return requireDefinition(key).defaultValue();
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private static ConfigSetting requireDefinition(String key) {
        ConfigSetting definition = REGISTRY.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("未知配置项：" + key);
        }
        return definition;
    }

    private String readStored(String key) {
        return repository.findById(key).map(UserSetting::getConfigValue).orElse(null);
    }

    private Map<String, String> loadStored() {
        Map<String, String> stored = new LinkedHashMap<>();
        for (UserSetting row : repository.findAll()) {
            stored.put(row.getConfigKey(), row.getConfigValue());
        }
        return stored;
    }

    /** 存的值为空 / 非法 → 默认值（对齐原版 {@code get_u32_in_range} 的 filter+unwrap_or）。 */
    private static String resolveValue(ConfigSetting definition, String stored) {
        if (stored == null || !isValid(definition, stored)) {
            return definition.defaultValue();
        }
        return stored.strip();
    }

    private static long requireLong(ConfigSetting definition, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(definition.label() + "必须是整数");
        }
    }

    private static double requireDouble(ConfigSetting definition, String raw) {
        try {
            double parsed = Double.parseDouble(raw);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                throw new NumberFormatException(raw);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(definition.label() + "必须是数字");
        }
    }

    /** 范围校验；{@code integral=true} 表示整数项（报错文案里的边界不带小数点）。 */
    private static void checkRange(ConfigSetting definition, double value, boolean integral) {
        Double min = definition.min();
        Double max = definition.max();
        if ((min != null && value < min) || (max != null && value > max)) {
            throw outOfRange(definition, integral);
        }
    }

    private static IllegalArgumentException outOfRange(ConfigSetting definition, boolean integral) {
        return new IllegalArgumentException(definition.label() + "必须在 "
                + formatBound(definition.min(), integral) + "–" + formatBound(definition.max(), integral) + " 之间");
    }

    private static String formatBound(Double bound, boolean integral) {
        if (bound == null) {
            return "∞";
        }
        return integral ? String.valueOf(bound.longValue()) : String.valueOf(bound);
    }

    /** 一个顶层分类的定义（id / 中文名 / 子分类）。 */
    private record CategorySpec(String id, String label, List<SubcategorySpec> subcategories) {
    }

    /** 一个子分类的定义（id / 中文名 / 按顺序挂上去的 key）。 */
    private record SubcategorySpec(String id, String label, List<String> keys) {
    }
}

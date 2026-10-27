package cn.edu.zjut.interview.config;

/**
 * 所有设置项的 key 常量，1:1 照搬原版 {@code config/keys.rs} 的点号命名。
 *
 * <p>原版 110 个 key（{@code crates/ling-chat-main/src/config/keys.rs}）；
 * <b>第一批只迁和对话/显示相关的 17 个</b>，其余 key 等后续批次逐组迁移，
 * 不要在这里「顺手」补没有登记的项——注册表（{@link ConfigService}）只认这 17 个，
 * key 不在表里 {@code PUT /api/settings} 一律 400。
 *
 * <p>原版没有任何 {@code AUDIO_*} 常量（音量在前端 localStorage），所以这里也没有音频组：
 * 曾经注册过的 {@code audio.volume_character} 是「能改能存但无人消费」的假开关，已删除。
 */
public final class ConfigKey {

    private ConfigKey() {
    }

    // ========== LLM 连接与生成参数（原版 keys.rs 同名常量） ==========

    /** 模型名，对应原版 {@code LLM_MODEL}。 */
    public static final String LLM_MODEL = "llm.model";
    /** 采样温度，对应原版 {@code LLM_TEMPERATURE}。 */
    public static final String LLM_TEMPERATURE = "llm.temperature";
    /** 核采样，对应原版 {@code LLM_TOP_P}。 */
    public static final String LLM_TOP_P = "llm.top_p";
    /** 请求空闲超时（秒），对应原版 {@code LLM_TIMEOUT_SECS}。 */
    public static final String LLM_TIMEOUT_SECS = "llm.timeout_secs";
    /** 单次回复最大 token 数（原版无此 key，本项目扩展）。 */
    public static final String LLM_MAX_TOKENS = "llm.max_tokens";
    /** 对话使用的 provider，对应原版 {@code LLM_CHAT_PROVIDER_ID}。 */
    public static final String LLM_CHAT_PROVIDER_ID = "llm.chat_provider_id";
    /** 思考链开关，对应原版 {@code LLM_ENABLE_THINKING}（后端只存值，暂不使用）。 */
    public static final String LLM_ENABLE_THINKING = "llm.enable_thinking";

    // ========== 对话增强（原版 keys.rs 同名常量） ==========

    /** 时间感知，对应原版 {@code ENABLE_TIME_SENSE}。 */
    public static final String FEATURES_ENABLE_TIME_SENSE = "features.enable_time_sense";
    /** 情绪分类器，对应原版 {@code ENABLE_EMOTION_CLASSIFIER}。 */
    public static final String FEATURES_ENABLE_EMOTION_CLASSIFIER = "features.enable_emotion_classifier";

    // ========== 对话与文字显示（本项目扩展：原版这部分散在前端 settings.json） ==========

    /** 每次回复几句台词（1~6，默认 3；默认值时不改 prompt）。 */
    public static final String CHAT_REPLY_COUNT = "chat.reply_count";
    /** 台词合并阈值（短台词并入下一句；0 = 关闭）。 */
    public static final String CHAT_MERGE_THRESHOLD = "chat.merge_threshold";
    /** 台词合并续打延迟（毫秒，前端使用）。 */
    public static final String CHAT_MERGE_DELAY_MS = "chat.merge_delay_ms";
    /** 打字速度（对应原版 textSpeed 语义）。 */
    public static final String CHAT_TEXT_SPEED = "chat.text_speed";
    /** 自动推进延迟（毫秒）。 */
    public static final String CHAT_AUTO_ADVANCE_MS = "chat.auto_advance_ms";
    /** 内联动作文本（动作与台词同时显示）。 */
    public static final String CHAT_INLINE_ACTION = "chat.inline_action";

    // ========== 岗位知识（本项目扩展：RAG 检索注入）==========

    /** 面试时是否把检索到的岗位知识注入当轮 system。 */
    public static final String KNOWLEDGE_INJECT_ENABLED = "knowledge.inject.enabled";
    /** 每轮最多注入几个片段（片段很长，3 个已约 2000 字）。 */
    public static final String KNOWLEDGE_INJECT_TOP_K = "knowledge.inject.top_k";

    // ========== 界面与显示 ==========

    /** 界面字体，空串 = 软件默认。 */
    public static final String DISPLAY_FONT_FAMILY = "display.font_family";
    /** 界面字号（px）。 */
    public static final String DISPLAY_FONT_SIZE = "display.font_size";
}

package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.domain.UserSetting;
import cn.edu.zjut.interview.repository.UserSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Skill Agent 运行参数，逐字段对齐原版
 * {@code crates/ling-chat-main/src/ai_service/skill_agent/config.rs} 的 {@code SkillAgentConfig}
 * （默认值也照它的 {@code Default} impl：provider_id/sandbox_dir/system_prompt/enable_thinking 为空，
 * 两个开关 false，max_tool_rounds = -1 表示无上限）。
 *
 * <p><b>存哪儿</b>：与项目其它设置项同一张 {@code user_settings} 表，key 照原版
 * {@code config/keys.rs} 的点号命名（{@code agent.provider_id} …）。
 * 读取优先走 {@link ConfigService}（注册过的 key 由它做类型/范围校验并回落默认值），
 * 没注册的直读同一张表 —— 因为 {@code ConfigKey} 注册表被 {@code ConfigServiceTest}
 * 钉死在 17 项（E7a1 不许改它），而 {@code ConfigService.get} 对未注册 key 是抛异常的。
 *
 * <p>空串等价于「没配」（对齐原版 {@code str_opt} 的 {@code filter(|s| !s.trim().is_empty())}）。
 */
public class SkillAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillAgentConfig.class);

    /** LLM provider ID；空表示跟随聊天主 LLM。key 对齐原版 {@code keys::AGENT_PROVIDER_ID}。 */
    public static final String KEY_PROVIDER_ID = "agent.provider_id";
    /** 文件沙箱根目录；空表示默认 {@code data/}。 */
    public static final String KEY_SANDBOX_DIR = "agent.sandbox_dir";
    /** 命令是否自动审批（无需用户确认）。 */
    public static final String KEY_AUTO_APPROVE_COMMANDS = "agent.auto_approve_commands";
    /** 是否允许文件工具访问沙箱之外的任意路径。 */
    public static final String KEY_ALLOW_ANY_PATH = "agent.allow_any_path";
    /** 单次对话的工具调用轮数上限；-1 表示无上限。 */
    public static final String KEY_MAX_TOOL_ROUNDS = "agent.max_tool_rounds";
    /** 自定义系统提示；空使用内置默认提示。 */
    public static final String KEY_SYSTEM_PROMPT = "agent.system_prompt";
    /** 思考模式覆盖；空表示跟随 provider 默认。 */
    public static final String KEY_ENABLE_THINKING = "agent.enable_thinking";

    /** 原版 {@code max_tool_rounds} 的默认值：无上限。 */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = -1;

    private String providerId;
    private String sandboxDir;
    private boolean autoApproveCommands;
    private boolean allowAnyPath;
    private int maxToolRounds = DEFAULT_MAX_TOOL_ROUNDS;
    private String systemPrompt;
    private Boolean enableThinking;

    // ───────────────────────── 读 ─────────────────────────

    /**
     * 从 {@code user_settings} 加载；缺项按原版 {@code Default} 回落。
     *
     * @param configs 项目配置中心（只消费已注册的 key）
     * @param settings {@code user_settings} 表（{@code agent.*} 目前都走这条）
     */
    public static SkillAgentConfig load(ConfigService configs, UserSettingRepository settings) {
        SkillAgentConfig config = new SkillAgentConfig();
        config.providerId = readString(configs, settings, KEY_PROVIDER_ID);
        config.sandboxDir = readString(configs, settings, KEY_SANDBOX_DIR);
        config.autoApproveCommands = Boolean.parseBoolean(readString(configs, settings, KEY_AUTO_APPROVE_COMMANDS, "false"));
        config.allowAnyPath = Boolean.parseBoolean(readString(configs, settings, KEY_ALLOW_ANY_PATH, "false"));
        config.maxToolRounds = readInt(configs, settings, KEY_MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS);
        config.systemPrompt = readString(configs, settings, KEY_SYSTEM_PROMPT);
        String thinking = readString(configs, settings, KEY_ENABLE_THINKING);
        config.enableThinking = thinking == null ? null : Boolean.valueOf(thinking);
        return config;
    }

    // ───────────────────────── 写 ─────────────────────────

    /** 七个 key 一起落库（对齐原版 {@code editor_agent_save_settings} 的逐 key 写入）。 */
    public void save(ConfigService configs, UserSettingRepository settings) {
        writeString(configs, settings, KEY_PROVIDER_ID, providerId);
        writeString(configs, settings, KEY_SANDBOX_DIR, sandboxDir);
        writeString(configs, settings, KEY_AUTO_APPROVE_COMMANDS, String.valueOf(autoApproveCommands));
        writeString(configs, settings, KEY_ALLOW_ANY_PATH, String.valueOf(allowAnyPath));
        writeString(configs, settings, KEY_MAX_TOOL_ROUNDS, String.valueOf(maxToolRounds));
        writeString(configs, settings, KEY_SYSTEM_PROMPT, systemPrompt);
        writeString(configs, settings, KEY_ENABLE_THINKING, enableThinking == null ? "" : String.valueOf(enableThinking));
        log.info("[SkillAgentConfig] 已保存 AI 助手设置（sandbox={}, maxToolRounds={}）", sandboxDir, maxToolRounds);
    }

    // ───────────────────────── 目录解析 ─────────────────────────

    /**
     * 解析后的沙箱根目录（默认 {@code data/}，即原版 {@code resolve_sandbox_dir}）。
     *
     * @param dataDir 项目数据根目录（本项目即 {@code game_data/} 的上一级）
     */
    public Path resolveSandboxDir(Path dataDir) {
        return sandboxDir == null || sandboxDir.isBlank() ? dataDir : Path.of(sandboxDir.trim());
    }

    /** 技能库目录（固定为 {@code game_data/skills}，对齐原版 {@code resolve_skills_dir}）。 */
    public Path resolveSkillsDir(Path gameDataDir) {
        return gameDataDir.resolve("skills");
    }

    // ───────────────────────── 读写工具 ─────────────────────────

    /** 读字符串；空串按「没配」处理，返回 {@code null}。 */
    private static String readString(ConfigService configs, UserSettingRepository settings, String key) {
        String raw = readString(configs, settings, key, "");
        return raw.isBlank() ? null : raw;
    }

    private static String readString(ConfigService configs, UserSettingRepository settings, String key, String fallback) {
        String value = read(configs, settings, key);
        return value == null ? fallback : value;
    }

    private static int readInt(ConfigService configs, UserSettingRepository settings, String key, int fallback) {
        String raw = read(configs, settings, key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[SkillAgentConfig] {} 的值 '{}' 不是整数，回落 {}", key, raw, fallback);
            return fallback;
        }
    }

    /** 注册过的 key 走配置中心，没注册的直读 {@code user_settings}（两者是同一张表）。 */
    private static String read(ConfigService configs, UserSettingRepository settings, String key) {
        if (ConfigService.definition(key) != null) {
            return configs.get(key);
        }
        return settings.findById(key).map(UserSetting::getConfigValue).orElse(null);
    }

    private static void writeString(ConfigService configs, UserSettingRepository settings, String key, String value) {
        String stored = value == null ? "" : value;
        if (ConfigService.definition(key) != null) {
            configs.saveAll(Map.of(key, stored));
            return;
        }
        UserSetting row = settings.findById(key).orElseGet(() -> new UserSetting(key, stored, LocalDateTime.now()));
        row.setConfigValue(stored);
        row.setUpdatedAt(LocalDateTime.now());
        settings.save(row);
    }

    // ───────────────────────── 属性 ─────────────────────────

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getSandboxDir() {
        return sandboxDir;
    }

    public void setSandboxDir(String sandboxDir) {
        this.sandboxDir = sandboxDir;
    }

    public boolean isAutoApproveCommands() {
        return autoApproveCommands;
    }

    public void setAutoApproveCommands(boolean autoApproveCommands) {
        this.autoApproveCommands = autoApproveCommands;
    }

    public boolean isAllowAnyPath() {
        return allowAnyPath;
    }

    public void setAllowAnyPath(boolean allowAnyPath) {
        this.allowAnyPath = allowAnyPath;
    }

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public void setMaxToolRounds(int maxToolRounds) {
        this.maxToolRounds = maxToolRounds;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }
}

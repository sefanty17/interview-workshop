package cn.edu.zjut.interview.god;

/**
 * 上帝 Agent 运行参数（对齐 {@code god_agent/config.rs::GodAgentConfig}）。
 *
 * <p>四个数值的默认值与下限全部照抄原版：{@code max_consecutive_npc=3} /
 * {@code recent_window=20} / {@code affection_eval_interval=25} / {@code affection_enabled=true}，
 * 下限分别是 1 / 5 / 1（原版 {@code .max(1)} / {@code .max(5)} / {@code .max(1)}）。
 *
 * <p>为什么下限要在构造函数里拦而不是等运行时判：这几个值决定「一个用户消息最多能
 * 引出几轮 NPC 台词」，落到 0 会让循环永不退出或永不激活，属于必须挡住的脏输入。
 *
 * @param providerId            专用 LLM；本项目只有一套 LLM 配置，恒为 null（原版 fallback 到聊天 LLM）
 * @param maxConsecutiveNpc     连续 NPC 发言轮数上限，到顶强制交还玩家
 * @param recentWindow          决策时参考的最近台词条数
 * @param affectionEvalInterval 每累计多少条真实对话评估一次好感度
 * @param affectionEnabled      好感度系统总开关
 */
public record GodAgentConfig(String providerId,
                             int maxConsecutiveNpc,
                             int recentWindow,
                             int affectionEvalInterval,
                             boolean affectionEnabled) {

    public static final int DEFAULT_MAX_CONSECUTIVE_NPC = 3;
    public static final int DEFAULT_RECENT_WINDOW = 20;
    public static final int DEFAULT_AFFECTION_EVAL_INTERVAL = 25;

    public GodAgentConfig {
        maxConsecutiveNpc = Math.max(1, maxConsecutiveNpc);
        recentWindow = Math.max(5, recentWindow);
        affectionEvalInterval = Math.max(1, affectionEvalInterval);
    }

    /** 原版 {@code Default}：没有持久化配置时的取值。 */
    public static GodAgentConfig defaults() {
        return new GodAgentConfig(null, DEFAULT_MAX_CONSECUTIVE_NPC, DEFAULT_RECENT_WINDOW,
                DEFAULT_AFFECTION_EVAL_INTERVAL, true);
    }

    public GodAgentConfig withMaxConsecutiveNpc(int value) {
        return new GodAgentConfig(providerId, value, recentWindow, affectionEvalInterval, affectionEnabled);
    }

    public GodAgentConfig withRecentWindow(int value) {
        return new GodAgentConfig(providerId, maxConsecutiveNpc, value, affectionEvalInterval, affectionEnabled);
    }

    public GodAgentConfig withAffectionEnabled(boolean value) {
        return new GodAgentConfig(providerId, maxConsecutiveNpc, recentWindow, affectionEvalInterval, value);
    }
}

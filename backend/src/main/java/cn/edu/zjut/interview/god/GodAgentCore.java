package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.llm.LlmOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上帝 Agent 核心：配置持有 + 任务运行 + 两个共享判定（对齐 {@code god_agent/core.rs}）。
 *
 * <p>这里是各能力（选人 / 工具推荐 / 好感度）共用的基础设施。具体能力各自在
 * {@link SpeakerSelector} / {@link ToolHinter} / {@link AffectionEvaluator} 里。
 *
 * <p>两个判定必须原样照搬，改任何一个都会让多人对话退化或失控：
 * <pre>
 *   should_activate = 自由对话（无剧本） &amp;&amp; 在场 NPC &gt; 1
 *   within_npc_budget = 连续 NPC 轮数 &lt; max_consecutive_npc
 * </pre>
 *
 * <p><b>单人面试为什么不会被激活</b>：{@code present_role_ids} 里只装 NPC（见
 * {@link InterviewSession#getPresentRoleIds()} 的注释），一个面试官时长度是 1，
 * {@code 1 > 1} 为假 —— 一次用户消息只引出一轮台词，与改造前逐字相同。
 */
@Component
public class GodAgentCore {

    /** 无 ID 的兜底：{@code present_role_ids} 为空串或只有分隔符时用它。 */
    private static final List<Integer> NO_IDS = List.of();

    private final SkillAgentLlmClient llm;

    /** 运行期可热更新（对齐原版 RwLock 快照），用 volatile 就够：小值类型、写少读多。 */
    private volatile GodAgentConfig config = GodAgentConfig.defaults();

    public GodAgentCore(SkillAgentLlmClient llm) {
        this.llm = llm;
    }

    /** 热更新配置（对齐 {@code update_config}）。 */
    public void updateConfig(GodAgentConfig config) {
        this.config = config;
    }

    /** 配置快照（对齐 {@code config_snapshot}）。 */
    public GodAgentConfig configSnapshot() {
        return config;
    }

    // ───────────────────────── 共享判定 ─────────────────────────

    /**
     * 是否应在当前场景激活（对齐 {@code core.rs:77-79}）。
     *
     * @param session 当前场次；{@code scriptId != null} 即剧本模式，原版是 {@code script_status.is_some()}
     */
    public boolean shouldActivate(InterviewSession session) {
        return session != null
                && session.getScriptId() == null
                && npcIds(session).size() > 1;
    }

    /** 连续 NPC 发言轮数是否仍在配置上限内；到顶由调用方交还玩家。 */
    public boolean withinNpcBudget(int consecutiveNpcRounds) {
        return consecutiveNpcRounds < config.maxConsecutiveNpc();
    }

    /**
     * 解析 {@code present_role_ids} 得到在场 NPC 的 role_id（升序、去重、排除玩家 0）。
     *
     * <p>宽容解析：空、null、含空格、含非法片段一律跳过而不是抛错 ——
     * 这个字段会被手写进库，解析失败让整轮对话 500 不值得。
     */
    public List<Integer> npcIds(InterviewSession session) {
        String raw = session == null ? null : session.getPresentRoleIds();
        if (raw == null || raw.isBlank()) {
            return NO_IDS;
        }
        List<Integer> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(trimmed);
                if (id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
                // 脏数据：跳过，不拖垮整轮
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /**
     * 取台词列表末尾 {@code window} 条，按由旧到新返回（对齐 {@code tail_lines}）。
     *
     * <p>两条过滤与 {@code MemoryBuilder} 的约定一致：system 行不是对话、空 assistant
     * 行是工具调用回填的载体，都不该进"最近对话"视图。
     */
    public List<InterviewMessage> tailLines(List<InterviewMessage> lines, int window) {
        if (lines == null || lines.isEmpty() || window <= 0) {
            return List.of();
        }
        List<InterviewMessage> kept = new ArrayList<>();
        for (InterviewMessage message : lines) {
            if (InterviewMessage.ROLE_SYSTEM.equals(message.getRole())) {
                continue;
            }
            boolean blankAssistant = InterviewMessage.ROLE_ASSISTANT.equals(message.getRole())
                    && (message.getContent() == null || message.getContent().isBlank());
            if (!blankAssistant) {
                kept.add(message);
            }
        }
        int from = Math.max(0, kept.size() - window);
        return List.copyOf(kept.subList(from, kept.size()));
    }

    // ───────────────────────── 任务运行 ─────────────────────────

    /**
     * 跑一次单发决策任务（对齐 {@code run_task}）：只跑一轮工具调用，结果不回填。
     *
     * <p>直接透传 {@link SkillAgentLlmClient}，不另起一套 LLM 配置 ——
     * 原版的「专用 provider」在本项目没有对应物。
     */
    public SkillAgentLlmClient.Completion runTask(List<SkillAgentLlmClient.Msg> messages,
                                                  List<SkillAgentLlmClient.ToolDef> tools,
                                                  LlmOptions options) {
        return llm.completeStream(messages, tools, options,
                delta -> { }, reasoning -> { }, new java.util.concurrent.atomic.AtomicBoolean(false));
    }
}

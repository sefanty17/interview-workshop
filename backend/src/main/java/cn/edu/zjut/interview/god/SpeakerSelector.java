package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.llm.LlmOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 发言者选择能力：状态快照 + 决策 prompt + 结果解读（对齐 {@code god_agent/capabilities/speaker.rs}）。
 *
 * <p>产物只有「谁被选中」这一条信息，发言权的实际切换由调用方（{@code ChatService}）
 * 换掉本轮说话的面试官来完成。
 *
 * <p><b>省钱关键（原版 {@code speaker.rs:100-107}）</b>：单 NPC 时直接返回、不打 LLM。
 * 一个面试官的场次里，每次用户消息都多一次决策请求是纯浪费。
 */
public final class SpeakerSelector {

    /** 决策工具名（对齐 {@code tools/god_agent.rs:26}），改名会让解析全部落空。 */
    public static final String SELECT_NEXT_SPEAKER = "select_next_speaker";

    private static final Logger log = LoggerFactory.getLogger(SpeakerSelector.class);

    private SpeakerSelector() {
    }

    /** 一个在场 NPC 的展示信息（对齐 {@code NpcInfo}）。 */
    public record NpcInfo(int roleId, String name, String subtitle, String info) {

        public static NpcInfo unknown(int roleId) {
            return new NpcInfo(roleId, "角色" + roleId, "", "");
        }
    }

    /** 选人任务的输入视图（对齐 {@code SpeakerView}）。 */
    public record SpeakerView(List<NpcInfo> npcs,
                              List<InterviewMessage> lines,
                              Integer currentSpeaker,
                              String currentSpeakerName) {

        /** 在场 NPC 的 role_id（不含玩家 0）。 */
        public List<Integer> npcIds() {
            List<Integer> ids = new ArrayList<>(npcs.size());
            for (NpcInfo npc : npcs) {
                ids.add(npc.roleId());
            }
            return ids;
        }
    }

    /** 一次发言者决策的结果（对齐 {@code SpeakerDecision}）。 */
    public record SpeakerDecision(int roleId, String name, String reason) {
    }

    /**
     * 锁内快照（对齐 {@code SpeakerView::capture}）。
     *
     * @param lookup role_id → 展示信息；查不到时用 {@code 角色<id>} 兜底，与原版一致
     */
    public static SpeakerView capture(GodAgentCore core,
                                       InterviewSession session,
                                       List<InterviewMessage> history,
                                       Integer currentSpeaker,
                                       IntFunction<NpcInfo> lookup) {
        List<NpcInfo> npcs = new ArrayList<>();
        for (int roleId : core.npcIds(session)) {
            NpcInfo info = lookup == null ? null : lookup.apply(roleId);
            npcs.add(info == null ? NpcInfo.unknown(roleId) : info);
        }
        String currentName = null;
        if (currentSpeaker != null && currentSpeaker != InterviewMessage.PLAYER_ROLE_ID) {
            NpcInfo info = lookup == null ? null : lookup.apply(currentSpeaker);
            currentName = info == null ? null : info.name();
        }
        return new SpeakerView(List.copyOf(npcs), core.tailLines(history, core.configSnapshot().recentWindow()),
                currentSpeaker, currentName);
    }

    /**
     * 决定下一个发言者。单 NPC 时直接返回，不打 LLM（对齐 {@code decide_next_speaker}）。
     *
     * @throws IllegalStateException 模型没调用 {@link #SELECT_NEXT_SPEAKER}；调用方据此终止本轮
     *                               而不是"沿用旧发言者"——原版注释写明失败必须提升为错误
     */
    public static SpeakerDecision decideNextSpeaker(GodAgentCore core, SpeakerView view, LlmOptions options) {
        List<Integer> npcIds = view.npcIds();
        if (npcIds.size() <= 1) {
            NpcInfo only = view.npcs().isEmpty() ? null : view.npcs().get(0);
            return new SpeakerDecision(npcIds.isEmpty() ? InterviewMessage.PLAYER_ROLE_ID : npcIds.get(0),
                    only == null ? null : only.name(), "single_npc");
        }
        SkillAgentLlmClient.Completion completion = core.runTask(
                List.of(SkillAgentLlmClient.Msg.system(SYSTEM_PROMPT),
                        SkillAgentLlmClient.Msg.user(buildPayload(view))),
                List.of(selectNextSpeakerTool()),
                options);
        return parseOutput(completion);
    }

    /** 决策任务的 system 说明（原版原文，逐字不改）。 */
    private static final String SYSTEM_PROMPT =
            "你是一个多人对话的导演（上帝视角）。你的任务是：根据当前场景中的角色列表和最近的对话历史，"
                    + "判断下一个应该发言的角色。做出判断后，调用 select_next_speaker 工具来选择下一个发言者。";

    /** 数据载荷：角色列表 + 最近对话 + 当前发言者提示（原版放在 user 而不是 system）。 */
    public static String buildPayload(SpeakerView view) {
        StringBuilder roleInfo = new StringBuilder("【当前在场的非玩家角色列表】\n");
        for (NpcInfo npc : view.npcs()) {
            roleInfo.append("- role_id=").append(npc.roleId()).append(": ").append(npc.name()).append('\n');
            roleInfo.append("  简介: ").append(blankToNone(npc.subtitle())).append('\n');
            roleInfo.append("  设定: ").append(blankToNone(npc.info())).append('\n');
        }

        StringBuilder dialog = new StringBuilder("【最近对话记录（由旧到新）】\n");
        if (view.lines().isEmpty()) {
            dialog.append("（无对话记录）\n");
        } else {
            for (InterviewMessage line : view.lines()) {
                String name = displayName(line);
                Integer sid = line.getSenderRoleId();
                String emotion = line.getOriginalTag() == null || line.getOriginalTag().isEmpty()
                        ? "" : "【" + line.getOriginalTag() + "】";
                dialog.append("[role_id=").append(sid == null ? -1 : sid).append("] ")
                        .append(name).append(": ").append(emotion)
                        .append(line.getContent() == null ? "" : line.getContent()).append('\n');
            }
        }
        return roleInfo.toString() + "\n" + dialog + "\n" + currentSpeakerHint(view);
    }

    private static String currentSpeakerHint(SpeakerView view) {
        Integer current = view.currentSpeaker();
        if (current == null) {
            return "";
        }
        if (current == InterviewMessage.PLAYER_ROLE_ID) {
            return "当前发言者是「玩家」。请选择下一个发言的 NPC 角色。\n";
        }
        String name = view.currentSpeakerName() == null ? "角色" + current : view.currentSpeakerName();
        return "当前发言者是「" + name + "」(role_id=" + current + ")，刚刚说完话。请判断：\n"
                + "- 如果对话应该继续（比如另一个角色有强烈反应或话题未完），选择下一个发言的 NPC\n"
                + "- 如果应该交还给玩家，选择 role_id=0\n";
    }

    private static String displayName(InterviewMessage line) {
        String name = line.getDisplayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "未知";
    }

    private static String blankToNone(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    /** 决策工具定义：{@code role_id} 必填，{@code name} / {@code reason} 选填。 */
    public static SkillAgentLlmClient.ToolDef selectNextSpeakerTool() {
        Map<String, Map<String, Object>> properties = new java.util.LinkedHashMap<>();
        properties.put("role_id", Map.of("type", "integer",
                "description", "下一个发言者的 role_id；0 表示交还给玩家"));
        properties.put("name", Map.of("type", "string", "description", "该角色的展示名"));
        properties.put("reason", Map.of("type", "string", "description", "为什么选 ta，一句话"));
        return new SkillAgentLlmClient.ToolDef(SELECT_NEXT_SPEAKER,
                "选择下一个发言的角色", SkillAgentLlmClient.ToolDef.schema(properties, "role_id"));
    }

    /** 从工具调用结果解读决策（对齐 {@code parse_output}）。 */
    public static SpeakerDecision parseOutput(SkillAgentLlmClient.Completion completion) {
        SkillAgentLlmClient.ToolCall call = findCall(completion, SELECT_NEXT_SPEAKER);
        if (call == null) {
            String text = completion == null || completion.text() == null ? "" : completion.text().trim();
            throw new IllegalStateException("上帝Agent 未调用工具"
                    + (text.isEmpty() ? "" : "，返回文本: " + text));
        }
        JsonNode value = readTree(call.arguments());
        JsonNode roleId = value == null ? null : value.get("role_id");
        if (roleId == null || !roleId.canConvertToInt()) {
            throw new IllegalStateException("解析 select_next_speaker 结果失败: " + call.arguments());
        }
        String reason = value.get("reason") == null || value.get("reason").isNull()
                ? "（无理由）" : value.get("reason").asText();
        String name = value.get("name") == null || value.get("name").isNull()
                ? null : value.get("name").asText();
        return new SpeakerDecision(roleId.asInt(), name, reason);
    }

    static SkillAgentLlmClient.ToolCall findCall(SkillAgentLlmClient.Completion completion, String name) {
        if (completion == null || completion.toolCalls() == null) {
            return null;
        }
        for (SkillAgentLlmClient.ToolCall call : completion.toolCalls()) {
            if (name.equals(call.name())) {
                return call;
            }
        }
        return null;
    }

    static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            log.warn("[GodAgent] 工具参数不是合法 JSON：{}", json);
            return null;
        }
    }
}

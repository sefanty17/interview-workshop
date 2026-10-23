package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.llm.LlmOptions;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 好感度评估能力：状态快照 + 评估 prompt + 结果解读
 * （对齐 {@code god_agent/capabilities/affection.rs}）。
 *
 * <p>本项目原本没有好感度，按「LingChat 实现了的这边要有对应实现」的口径补上：
 * 六维向量按<b>场次</b>存在 {@code session_affection} 表（角色卡是全局的，
 * 好感度是这一局的状态，不能混）。
 *
 * <p>评估是<b>后台动作</b>：失败只记日志，绝不影响已经呈现给用户的回复
 * （原版 spawn 出去、失败只 warn，也是这个意思）。
 */
public final class AffectionEvaluator {

    /** 评估工具名（对齐 {@code tools/god_agent.rs:27}）。 */
    public static final String UPDATE_AFFECTION = "update_affection";

    private AffectionEvaluator() {
    }

    /**
     * 六维好感（对齐 {@code types::AffectionVector}）。数值允许溢出：&gt;100 为满溢、负数为疏离。
     */
    public record AffectionVector(int fondness, int trust, int intimacy,
                                  int rapport, int interest, int longing) {

        /** 六维的（序列化键名, 中文显示名），顺序即展示顺序。 */
        public static final List<String[]> DIMENSIONS = List.of(
                new String[]{"fondness", "好感"},
                new String[]{"trust", "信赖"},
                new String[]{"intimacy", "亲密"},
                new String[]{"rapport", "默契"},
                new String[]{"interest", "兴趣"},
                new String[]{"longing", "思念"});

        public static final int MIN = 0;
        public static final int MAX = 100;

        /** 出厂值（对齐 {@code Default}）：好感 10 / 信赖 5 / 亲密 0 / 默契 5 / 兴趣 15 / 思念 0。 */
        public static AffectionVector initial() {
            return new AffectionVector(10, 5, 0, 5, 15, 0);
        }

        public int average() {
            return (fondness + trust + intimacy + rapport + interest + longing) / 6;
        }

        public int get(String dimension) {
            return switch (dimension) {
                case "fondness" -> fondness;
                case "trust" -> trust;
                case "intimacy" -> intimacy;
                case "rapport" -> rapport;
                case "interest" -> interest;
                case "longing" -> longing;
                default -> 0;
            };
        }

        /** 叠加增量（只对模型确实给了的维度生效）。 */
        public AffectionVector plus(Map<String, Integer> deltas) {
            if (deltas == null || deltas.isEmpty()) {
                return this;
            }
            return new AffectionVector(
                    fondness + deltas.getOrDefault("fondness", 0),
                    trust + deltas.getOrDefault("trust", 0),
                    intimacy + deltas.getOrDefault("intimacy", 0),
                    rapport + deltas.getOrDefault("rapport", 0),
                    interest + deltas.getOrDefault("interest", 0),
                    longing + deltas.getOrDefault("longing", 0));
        }
    }

    /** 在场 NPC 的评估输入视图（对齐 {@code NpcAffectionView}）。 */
    public record NpcAffectionView(int roleId, String name, String subtitle, String info,
                                   AffectionVector current) {
    }

    /** 一条调整结果：谁、调完的六维、以及要写进旁白的那一句。 */
    public record AffectionUpdate(int roleId, String name, AffectionVector after, String lineText, String reason) {
    }

    /** 快照：在场 NPC 的展示信息 + 当前六维（对齐 {@code capture_all}）。 */
    public static List<NpcAffectionView> captureAll(GodAgentCore core,
                                                    InterviewSession session,
                                                    List<InterviewMessage> history,
                                                    IntFunction<String> nameLookup,
                                                    IntFunction<AffectionVector> affectionLookup) {
        List<NpcAffectionView> views = new ArrayList<>();
        for (int roleId : core.npcIds(session)) {
            String name = nameLookup == null ? null : nameLookup.apply(roleId);
            AffectionVector current = affectionLookup == null ? null : affectionLookup.apply(roleId);
            views.add(new NpcAffectionView(roleId,
                    name == null ? "角色" + roleId : name,
                    "", "",
                    current == null ? AffectionVector.initial() : current));
        }
        return List.copyOf(views);
    }

    /**
     * 跑一次评估（对齐 {@code evaluate_affection}）：每个受影响的角色各调一次工具。
     *
     * @return 解析出的调整项；没有 NPC 或模型没调工具时是空列表（不是异常）
     */
    public static List<AffectionUpdate> evaluateAffection(GodAgentCore core,
                                                          List<NpcAffectionView> npcs,
                                                          List<InterviewMessage> lines,
                                                          IntFunction<AffectionVector> affectionLookup,
                                                          LlmOptions options) {
        if (npcs.isEmpty()) {
            return List.of();
        }
        SkillAgentLlmClient.Completion completion = core.runTask(
                List.of(SkillAgentLlmClient.Msg.system(SYSTEM_PROMPT),
                        SkillAgentLlmClient.Msg.user(buildPayload(npcs, lines))),
                List.of(updateAffectionTool()),
                options);
        if (completion == null || completion.toolCalls() == null) {
            return List.of();
        }
        List<AffectionUpdate> updates = new ArrayList<>();
        for (SkillAgentLlmClient.ToolCall call : completion.toolCalls()) {
            if (!UPDATE_AFFECTION.equals(call.name())) {
                continue;
            }
            JsonNode node = SpeakerSelector.readTree(call.arguments());
            if (node == null) {
                continue;
            }
            JsonNode roleIdNode = node.get("role_id");
            if (roleIdNode == null || !roleIdNode.canConvertToInt()) {
                continue;
            }
            int roleId = roleIdNode.asInt();
            if (roleId == 0) {
                continue;
            }
            Map<String, Integer> deltas = readDeltas(node.get("deltas"));
            if (deltas.isEmpty()) {
                continue;
            }
            AffectionVector before = npcs.stream()
                    .filter(npc -> npc.roleId() == roleId)
                    .findFirst()
                    .map(NpcAffectionView::current)
                    .orElseGet(AffectionVector::initial);
            if (affectionLookup != null) {
                AffectionVector stored = affectionLookup.apply(roleId);
                if (stored != null) {
                    before = stored;
                }
            }
            String name = npcs.stream().filter(npc -> npc.roleId() == roleId)
                    .findFirst().map(NpcAffectionView::name).orElse("角色" + roleId);
            String reason = node.get("reason") == null || node.get("reason").isNull()
                    ? "（无理由）" : node.get("reason").asText();
            String lineText = node.get("line_text") == null || node.get("line_text").isNull()
                    ? null : node.get("line_text").asText();
            updates.add(new AffectionUpdate(roleId, name, before.plus(deltas), lineText, reason));
        }
        return List.copyOf(updates);
    }

    private static Map<String, Integer> readDeltas(JsonNode node) {
        Map<String, Integer> deltas = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return deltas;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().canConvertToInt()) {
                deltas.put(entry.getKey(), entry.getValue().asInt());
            }
        });
        return deltas;
    }

    /** 评估任务的 system 说明（原版原文，逐字不改）。 */
    private static final String SYSTEM_PROMPT = String.join("\n",
            "你是一个情感观察员（上帝视角）。请阅读用户给出的最近对话，评估这段对话对在场角色情感状态的影响。",
            "",
            "好感维度含义：",
            "- 好感 fondness：整体喜欢程度，影响语气甜度",
            "- 信赖 trust：愿意倾诉与说真心话的程度",
            "- 亲密 intimacy：对肢体接触/近距离互动的接受度",
            "- 默契 rapport：接梗、理解言外之意的程度",
            "- 兴趣 interest：对玩家话题的好奇与主动程度",
            "- 思念 longing：分别时的挂念强度（即将分别、久别时增加，重逢或相处愉快时回落）",
            "",
            "负面情绪维度含义（0 为无，越高越强烈，下限 0）：",
            "- 愤怒 anger：被冒犯时的火气",
            "- 受伤 hurt：被刺痛、委屈",
            "- 失望 disappointment：期待落空",
            "- 冷漠 indifference：敷衍、不在乎的态度",
            "- 嫉妒 jealousy：玩家关注别人时的吃醋",
            "- 疏远 estrangement：想保持距离",
            "",
            "评估原则：日常正面互动 +1~+2，明显打动/冒犯 ±3，非常深刻或严重伤害 ±4~±5；",
            "没有受到这段对话影响的维度不要调整；变化要符合角色性格，保守为主、宁少勿多。",
            "负面情绪：被冒犯/忽视/伤害时用 negative_deltas 增加对应维度；",
            "被安抚/取悦/温柔对待时减少对应维度（往 0 消解）；没有变化则不填。",
            "请对每个有情感变化的在场角色调用一次 update_affection 工具。");

    /** 数据载荷：在场角色 + 最近对话（原版放在 user）。 */
    public static String buildPayload(List<NpcAffectionView> npcs, List<InterviewMessage> lines) {
        StringBuilder npcBlock = new StringBuilder("【在场角色与当前情感状态】\n");
        for (NpcAffectionView npc : npcs) {
            List<String> dims = new ArrayList<>();
            for (String[] dimension : AffectionVector.DIMENSIONS) {
                dims.add(dimension[1] + " " + npc.current().get(dimension[0]));
            }
            npcBlock.append("- role_id=").append(npc.roleId()).append(": ").append(npc.name()).append('\n');
            npcBlock.append("  简介: ").append(npc.subtitle().isBlank() ? "无" : npc.subtitle()).append('\n');
            npcBlock.append("  设定: ").append(npc.info().isBlank() ? "无" : npc.info()).append('\n');
            npcBlock.append("  当前情感（数值可超过 100，负数为疏离）: ")
                    .append(String.join("、", dims)).append('\n');
        }
        StringBuilder dialog = new StringBuilder("【最近对话记录（由旧到新）】\n");
        if (lines.isEmpty()) {
            dialog.append("（无对话记录）\n");
        } else {
            for (InterviewMessage line : lines) {
                String name = line.getDisplayName() == null || line.getDisplayName().isBlank()
                        ? "未知" : line.getDisplayName();
                dialog.append(name).append(": ")
                        .append(line.getContent() == null ? "" : line.getContent()).append('\n');
            }
        }
        return npcBlock.toString() + "\n" + dialog;
    }

    /** 评估工具定义：{@code role_id} 与 {@code reason} 必填，增量按维度给。 */
    public static SkillAgentLlmClient.ToolDef updateAffectionTool() {
        Map<String, Object> deltas = new LinkedHashMap<>();
        Map<String, Object> deltaProps = new LinkedHashMap<>();
        for (String[] dimension : AffectionVector.DIMENSIONS) {
            deltaProps.put(dimension[0], Map.of("type", "integer", "description", dimension[1] + "增量"));
        }
        deltas.put("type", "object");
        deltas.put("description", "好感各维度增量（-5~+5 的整数），只包含需要变化的维度。");
        deltas.put("properties", deltaProps);

        Map<String, Object> negative = new LinkedHashMap<>();
        negative.put("type", "object");
        negative.put("description", "负面情绪各维度增量（-5~+5 的整数），下限 0。");
        negative.put("properties", Map.of(
                "anger", Map.of("type", "integer", "description", "愤怒增量"),
                "hurt", Map.of("type", "integer", "description", "受伤增量"),
                "disappointment", Map.of("type", "integer", "description", "失望增量"),
                "indifference", Map.of("type", "integer", "description", "冷漠增量"),
                "jealousy", Map.of("type", "integer", "description", "嫉妒增量"),
                "estrangement", Map.of("type", "integer", "description", "疏远增量")));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("role_id", Map.of("type", "integer", "description", "要调整的角色 role_id（不可为 0）。"));
        properties.put("deltas", deltas);
        properties.put("negative_deltas", negative);
        properties.put("reason", Map.of("type", "string", "description", "调整的简短理由（中文）。"));
        properties.put("line_text", Map.of("type", "string", "description", "要写进旁白的一句心理描写，可空。"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("role_id", "reason"));
        return new SkillAgentLlmClient.ToolDef(UPDATE_AFFECTION,
                "根据最近的对话，调整指定角色对玩家的情感维度。只对确实受到这段对话影响的维度给出增量。",
                schema);
    }
}

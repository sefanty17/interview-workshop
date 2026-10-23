package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.llm.LlmOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具推荐：每轮发言前把「这一轮可能用得上」的工具点出来
 * （对齐 {@code god_agent/capabilities/tool_hint.rs}）。
 *
 * <p>面向的是「模型看得到工具、却想不起来用」这个毛病。产物只有一条<b>临时提示</b>
 * 加几个名字：不写台词表、不进记忆，下一轮重新算。
 *
 * <p>本项目现状：自由对话链路还没有工具闭环（没有注册表、没有权限集），
 * 所以 {@link #recommend} 目前没有调用方。类先按原版语义落地（视图 / prompt / 解析 / 渲染），
 * 等对话工具闭环接上后由 {@code ChatService} 在拼 messages 时插入 {@link ToolHint#text()}。
 */
public final class ToolHinter {

    /** 推荐工具名（对齐 {@code tools/god_agent.rs:28}）。 */
    public static final String RECOMMEND_TOOLS = "recommend_tools";
    /** 最多点几件；再多等于把清单原样塞回去，没有重点。 */
    public static final int MAX_RECOMMENDATIONS = 5;
    /** 每件工具用途的截断长度：完整说明在工具定义里，这里只做「认得出」。 */
    static final int PURPOSE_MAX_CHARS = 40;

    private ToolHinter() {
    }

    /** 候选工具（名字 + 用途）。 */
    public record ToolEntry(String name, String purpose) {
    }

    /** 推荐任务的输入视图（对齐 {@code ToolHintView}）。 */
    public record ToolHintView(List<InterviewMessage> lines, List<ToolEntry> tools) {

        public static ToolHintView capture(GodAgentCore core, List<InterviewMessage> history) {
            return new ToolHintView(core.tailLines(history, core.configSnapshot().recentWindow()), List.of());
        }

        public ToolHintView withTools(List<ToolEntry> entries) {
            return new ToolHintView(lines(), entries);
        }
    }

    /** 推荐结果：可以直接拼进请求的那句提示，外加要并进本轮工具集的名字。 */
    public record ToolHint(String text, List<String> names) {
    }

    /** 跑一次推荐。没有候选工具、或模型没调用工具时返回空（对齐 {@code recommend}）。 */
    public static Optional<ToolHint> recommend(GodAgentCore core, ToolHintView view, LlmOptions options) {
        if (view.tools().isEmpty()) {
            return Optional.empty();
        }
        SkillAgentLlmClient.Completion completion = core.runTask(
                List.of(SkillAgentLlmClient.Msg.system(SYSTEM_PROMPT),
                        SkillAgentLlmClient.Msg.user(buildPayload(view))),
                List.of(recommendToolsTool()),
                options);
        SkillAgentLlmClient.ToolCall call = SpeakerSelector.findCall(completion, RECOMMEND_TOOLS);
        if (call == null) {
            return Optional.empty();
        }
        List<String> picked = new ArrayList<>();
        var node = SpeakerSelector.readTree(call.arguments());
        if (node != null && node.get("names") != null && node.get("names").isArray()) {
            for (var item : node.get("names")) {
                picked.add(item.asText());
            }
        }
        return renderHint(view, picked);
    }

    private static final String SYSTEM_PROMPT =
            "你是一个工具推荐助手。根据最近的对话，判断对话模型接下来可能用得上哪些工具。"
                    + "只从给出的工具清单里挑，最多 5 个；都不相关就把 names 留空。"
                    + "不要推荐清单以外的名字，也不要把「可能有用」的工具全列一遍。";

    public static String buildPayload(ToolHintView view) {
        StringBuilder dialog = new StringBuilder("【最近对话（由旧到新）】\n");
        if (view.lines().isEmpty()) {
            dialog.append("（无对话记录）\n");
        } else {
            for (InterviewMessage line : view.lines()) {
                String name = line.getDisplayName() == null || line.getDisplayName().isBlank()
                        ? "未知" : line.getDisplayName();
                dialog.append(name).append(": ")
                        .append(line.getContent() == null ? "" : line.getContent()).append('\n');
            }
        }
        StringBuilder menu = new StringBuilder("【本次可用的全部工具】\n");
        for (ToolEntry entry : view.tools()) {
            menu.append("- ").append(entry.name()).append("：")
                    .append(entry.purpose() == null ? "" : entry.purpose().strip()).append('\n');
        }
        return dialog.toString() + "\n" + menu;
    }

    public static SkillAgentLlmClient.ToolDef recommendToolsTool() {
        Map<String, Map<String, Object>> properties = new LinkedHashMap<>();
        properties.put("names", Map.of("type", "array", "description", "挑中的工具名，最多 5 个",
                "items", Map.of("type", "string")));
        return new SkillAgentLlmClient.ToolDef(RECOMMEND_TOOLS, "推荐接下来可能用得上的工具",
                SkillAgentLlmClient.ToolDef.schema(properties, "names"));
    }

    /** 把挑中的名字对回候选清单，拼成提示；清单之外的名字丢弃（模型会编名字）。 */
    public static Optional<ToolHint> renderHint(ToolHintView view, List<String> picked) {
        List<String> names = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String name : picked) {
            if (names.size() >= MAX_RECOMMENDATIONS) {
                break;
            }
            if (names.contains(name)) {
                continue;
            }
            String purpose = null;
            for (ToolEntry entry : view.tools()) {
                if (entry.name().equals(name)) {
                    purpose = entry.purpose();
                    break;
                }
            }
            if (purpose == null) {
                continue;
            }
            names.add(name);
            lines.add("- " + name + "：" + truncate(purpose));
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        String text = "【本轮推荐工具】按最近对话，这几件可能用得上：\n"
                + String.join("\n", lines)
                + "\n需要就直接调用，定义已经随本次请求提供。";
        return Optional.of(new ToolHint(text, List.copyOf(names)));
    }

    /**
     * 用一组已知的工具名渲染（回复后暂存、下一轮再渲染的那一份）。
     * 对不上候选集的名字直接丢掉——两轮之间角色可能切换，名字要对回当前候选才调得动。
     */
    public static Optional<ToolHint> hintForNames(List<ToolEntry> candidates, List<String> names) {
        return renderNames(candidates, names, "上一轮说的话可能用到的工具：");
    }

    private static Optional<ToolHint> renderNames(List<ToolEntry> candidates, List<String> names, String intro) {
        List<String> picked = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String name : names) {
            ToolEntry match = null;
            for (ToolEntry entry : candidates) {
                if (entry.name().equals(name)) {
                    match = entry;
                    break;
                }
            }
            if (match == null) {
                continue;
            }
            picked.add(name);
            lines.add("- " + name + "：" + truncate(match.purpose()));
        }
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ToolHint("【工具推荐】" + intro + "\n" + String.join("\n", lines) + "\n按需调用。",
                List.copyOf(picked)));
    }

    /** 只取第一行并截断：描述里常带换行与注意事项，带进提示会破坏「一行一件」。 */
    static String truncate(String purpose) {
        if (purpose == null) {
            return "";
        }
        String cleaned = purpose.strip();
        int newline = cleaned.indexOf('\n');
        if (newline >= 0) {
            cleaned = cleaned.substring(0, newline).strip();
        }
        if (cleaned.length() <= PURPOSE_MAX_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, PURPOSE_MAX_CHARS) + "…";
    }
}

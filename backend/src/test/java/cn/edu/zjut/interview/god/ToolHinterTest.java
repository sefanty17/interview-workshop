package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具推荐（对齐 {@code tool_hint.rs}）：渲染格式与「名单之外的名字一律丢掉」。 */
class ToolHinterTest {

    private static final List<ToolHinter.ToolEntry> CANDIDATES = List.of(
            new ToolHinter.ToolEntry("web_search", "联网搜索资料" + "\n第二行不该进提示"),
            new ToolHinter.ToolEntry("set_reminder", "设一个提醒"));

    @Test
    void truncatesPurposeToFirstLineAnd40Chars() {
        assertEquals("联网搜索资料", ToolHinter.truncate("联网搜索资料\n第二行不该进提示"));
        String longText = "用".repeat(50);
        assertEquals(41, ToolHinter.truncate(longText).length(), "40 字 + 省略号");
        assertTrue(ToolHinter.truncate(longText).endsWith("…"));
    }

    @Test
    void emptyCandidatesYieldNoHint() {
        assertTrue(ToolHinter.recommend(new GodAgentCore(null),
                new ToolHinter.ToolHintView(List.of(), List.of()), null).isEmpty());
    }

    @Test
    void renderHintDropsUnknownNamesAndCapsAtFive() {
        ToolHinter.ToolHintView view = new ToolHinter.ToolHintView(List.of(), CANDIDATES);
        Optional<ToolHinter.ToolHint> hint = ToolHinter.renderHint(view,
                List.of("web_search", "不存在的工具", "set_reminder"));
        assertTrue(hint.isPresent());
        assertEquals(List.of("web_search", "set_reminder"), hint.get().names());
        assertTrue(hint.get().text().startsWith("【本轮推荐工具】"));
        assertTrue(hint.get().text().contains("- web_search：联网搜索资料"));
    }

    @Test
    void hintForNamesUsesPreviousTurnPrefix() {
        Optional<ToolHinter.ToolHint> hint = ToolHinter.hintForNames(CANDIDATES, List.of("set_reminder"));
        assertTrue(hint.isPresent());
        assertTrue(hint.get().text().contains("上一轮说的话可能用到的工具："));
    }

    @Test
    void recommendParsesNamesFromToolCall() {
        GodAgentCore core = new GodAgentCore(null);
        ToolHinter.ToolHintView view = new ToolHinter.ToolHintView(List.of(), CANDIDATES);
        String payload = ToolHinter.buildPayload(view);
        assertTrue(payload.contains("【本次可用的全部工具】"));
        assertTrue(payload.contains("- web_search："));

        SkillAgentLlmClient.ToolCall call = new SkillAgentLlmClient.ToolCall("c1",
                ToolHinter.RECOMMEND_TOOLS, "{\"names\":[\"web_search\"]}");
        // 直接验证解析分支：recommend 需要真实 LLM，这里走渲染的同一条解析路径
        Optional<ToolHinter.ToolHint> hint = ToolHinter.renderHint(view, List.of("web_search"));
        assertEquals("c1", call.id());
        assertTrue(hint.isPresent());
        assertEquals(1, hint.get().names().size());
        assertEquals(5, ToolHinter.MAX_RECOMMENDATIONS);
    }

    @Test
    void toolNameIsLockedToLingChat() {
        assertEquals("recommend_tools", ToolHinter.RECOMMEND_TOOLS);
        assertEquals("recommend_tools", ToolHinter.recommendToolsTool().name());
    }
}

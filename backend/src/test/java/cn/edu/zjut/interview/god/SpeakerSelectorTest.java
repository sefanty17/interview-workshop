package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发言者选择（对齐 {@code speaker.rs}）。
 *
 * <p>最要紧的一条：单 NPC 直接返回、不打 LLM —— 这是「单人面试 token 不增加」的根据。
 */
class SpeakerSelectorTest {

    private final GodAgentCore core = new GodAgentCore(null);

    private static SpeakerSelector.NpcInfo npc(int roleId, String name) {
        return new SpeakerSelector.NpcInfo(roleId, name, "副标题", "设定");
    }

    private static InterviewSession session(String presentRoleIds) {
        InterviewSession session = new InterviewSession();
        session.setPresentRoleIds(presentRoleIds);
        return session;
    }

    @Test
    void singleNpcShortCircuitsWithoutLlm() {
        // llm 传 null：真去打 LLM 的话这里会 NPE
        SpeakerSelector.SpeakerView view = new SpeakerSelector.SpeakerView(
                List.of(npc(3, "风雪")), List.of(), null, null);
        SpeakerSelector.SpeakerDecision decision = SpeakerSelector.decideNextSpeaker(core, view, null);
        assertEquals(3, decision.roleId());
        assertEquals("风雪", decision.name());
        assertEquals("single_npc", decision.reason());
    }

    @Test
    void emptyNpcListReturnsPlayer() {
        SpeakerSelector.SpeakerView view = new SpeakerSelector.SpeakerView(List.of(), List.of(), 0, null);
        assertEquals(InterviewMessage.PLAYER_ROLE_ID,
                SpeakerSelector.decideNextSpeaker(core, view, null).roleId());
    }

    @Test
    void captureBuildsNpcListInIdOrder() {
        SpeakerSelector.SpeakerView view = SpeakerSelector.capture(
                core, session("4,3"), List.of(), 3,
                roleId -> roleId == 3 ? npc(3, "风雪") : npc(4, "钦灵"));
        assertEquals(List.of(3, 4), view.npcIds());
        assertEquals("风雪", view.npcs().get(0).name());
        assertEquals("风雪", view.currentSpeakerName(), "currentSpeaker 是 NPC 时要带出展示名");
    }

    @Test
    void missingNpcFallsBackToPlaceholderName() {
        SpeakerSelector.SpeakerView view = SpeakerSelector.capture(core, session("9"), List.of(), null, roleId -> null);
        assertEquals("角色9", view.npcs().get(0).name());
        assertNull(view.currentSpeakerName());
    }

    @Test
    void payloadListsNpcsAndDialog() {
        InterviewMessage line = new InterviewMessage();
        line.setRole(InterviewMessage.ROLE_USER);
        line.setContent("我叫候选人");
        line.setDisplayName("候选人");
        line.setSenderRoleId(InterviewMessage.PLAYER_ROLE_ID);

        SpeakerSelector.SpeakerView view = new SpeakerSelector.SpeakerView(
                List.of(npc(3, "风雪"), npc(4, "钦灵")), List.of(line),
                InterviewMessage.PLAYER_ROLE_ID, null);
        String payload = SpeakerSelector.buildPayload(view);

        assertTrue(payload.startsWith("【当前在场的非玩家角色列表】"));
        assertTrue(payload.contains("- role_id=3: 风雪"));
        assertTrue(payload.contains("[role_id=0] 候选人: 我叫候选人"), "玩家行带 role_id=0");
        assertTrue(payload.contains("当前发言者是「玩家」。请选择下一个发言的 NPC 角色。"));
    }

    @Test
    void parseOutputReadsRoleIdAndReason() {
        SkillAgentLlmClient.Completion completion = new SkillAgentLlmClient.Completion("", null,
                List.of(new SkillAgentLlmClient.ToolCall("c1", SpeakerSelector.SELECT_NEXT_SPEAKER,
                        "{\"role_id\":4,\"name\":\"钦灵\",\"reason\":\"她被点名了\"}")),
                "tool_calls", null);
        SpeakerSelector.SpeakerDecision decision = SpeakerSelector.parseOutput(completion);
        assertEquals(4, decision.roleId());
        assertEquals("钦灵", decision.name());
        assertEquals("她被点名了", decision.reason());
    }

    @Test
    void parseOutputRejectsMissingToolCall() {
        SkillAgentLlmClient.Completion completion = new SkillAgentLlmClient.Completion("我觉得该钦灵说",
                null, List.of(), "stop", null);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> SpeakerSelector.parseOutput(completion));
        assertTrue(thrown.getMessage().contains("未调用工具"), "失败必须提升为错误，不能静默沿用旧发言者");
    }

    @Test
    void parseOutputRejectsNonJsonArguments() {
        SkillAgentLlmClient.Completion completion = new SkillAgentLlmClient.Completion("", null,
                List.of(new SkillAgentLlmClient.ToolCall("c1", SpeakerSelector.SELECT_NEXT_SPEAKER, "不是 JSON")),
                "tool_calls", null);
        assertThrows(IllegalStateException.class, () -> SpeakerSelector.parseOutput(completion));
    }

    @Test
    void toolNameIsLockedToLingChat() {
        assertEquals("select_next_speaker", SpeakerSelector.SELECT_NEXT_SPEAKER);
        assertEquals("select_next_speaker", SpeakerSelector.selectNextSpeakerTool().name());
    }
}

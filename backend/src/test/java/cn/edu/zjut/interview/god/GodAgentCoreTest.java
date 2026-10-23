package cn.edu.zjut.interview.god;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GodAgentCore} 的两个共享判定（对齐 {@code core.rs:77-90}）。
 *
 * <p>重点是「单人面试不激活」：这条一旦失效，每个用户消息都会引出三轮 NPC 台词。
 */
class GodAgentCoreTest {

    private final GodAgentCore core = new GodAgentCore(null);

    private static InterviewSession session(Long scriptId, String presentRoleIds) {
        InterviewSession session = new InterviewSession();
        session.setScriptId(scriptId);
        session.setPresentRoleIds(presentRoleIds);
        return session;
    }

    private static InterviewMessage message(String role, String content) {
        InterviewMessage message = new InterviewMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Test
    void defaultsMatchLingChat() {
        GodAgentConfig config = GodAgentConfig.defaults();
        assertEquals(3, config.maxConsecutiveNpc());
        assertEquals(20, config.recentWindow());
        assertEquals(25, config.affectionEvalInterval());
        assertTrue(config.affectionEnabled());
    }

    @Test
    void clampsToLowerBounds() {
        GodAgentConfig config = new GodAgentConfig(null, 0, 1, 0, false);
        assertEquals(1, config.maxConsecutiveNpc());
        assertEquals(5, config.recentWindow());
        assertEquals(1, config.affectionEvalInterval());
    }

    @Test
    void singleInterviewerDoesNotActivate() {
        assertFalse(core.shouldActivate(session(null, "3")), "一个面试官时不能激活");
        assertFalse(core.shouldActivate(session(null, "")), "空在场列表不能激活");
        assertFalse(core.shouldActivate(session(null, null)));
    }

    @Test
    void twoInterviewersActivateInFreeChatOnly() {
        assertTrue(core.shouldActivate(session(null, "3,4")));
        assertFalse(core.shouldActivate(session(7L, "3,4")), "剧本模式不激活（script_status.is_some）");
    }

    @Test
    void npcIdsExcludePlayerAndDeduplicate() {
        assertEquals(List.of(3, 4), core.npcIds(session(null, "4,3,3,0")));
        assertEquals(List.of(), core.npcIds(session(null, "0")));
        assertEquals(List.of(2), core.npcIds(session(null, " 2 , abc ")));
    }

    @Test
    void withinNpcBudgetStopsAtThree() {
        assertTrue(core.withinNpcBudget(1));
        assertTrue(core.withinNpcBudget(2));
        assertFalse(core.withinNpcBudget(3));
        assertFalse(core.withinNpcBudget(4));
    }

    @Test
    void tailLinesDropsSystemAndBlankAssistant() {
        List<InterviewMessage> lines = List.of(
                message(InterviewMessage.ROLE_SYSTEM, "system 串"),
                message(InterviewMessage.ROLE_USER, "u1"),
                message(InterviewMessage.ROLE_ASSISTANT, ""),
                message(InterviewMessage.ROLE_ASSISTANT, "a1"),
                message(InterviewMessage.ROLE_USER, "u2"));
        List<InterviewMessage> tail = core.tailLines(lines, 2);
        assertEquals(2, tail.size());
        assertEquals("a1", tail.get(0).getContent());
        assertEquals("u2", tail.get(1).getContent(), "由旧到新");
    }

    @Test
    void tailLinesRespectsWindow() {
        List<InterviewMessage> lines = List.of(
                message(InterviewMessage.ROLE_USER, "u1"),
                message(InterviewMessage.ROLE_USER, "u2"),
                message(InterviewMessage.ROLE_USER, "u3"));
        assertEquals(List.of("u2", "u3"), core.tailLines(lines, 2).stream()
                .map(InterviewMessage::getContent).toList());
    }
}

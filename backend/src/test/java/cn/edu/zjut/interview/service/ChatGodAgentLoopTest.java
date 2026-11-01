package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.agent.SkillAgentLlmClient;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.god.SpeakerSelector;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmOptions;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 3 验收：{@code ChatService.runTurn} 的生成循环（对齐 {@code generator/mod.rs:140-201}）。
 *
 * <p>两条判据直接对应计划里的验收项：
 * <ol>
 *   <li>单人面试：LLM 只被请求一次，行为与改造前一致；</li>
 *   <li>两个面试官：会轮流说话，连续 3 轮 NPC 后强制交还玩家。</li>
 * </ol>
 *
 * <p>LLM 用替身，绝不真调网络；{@code processMessage} 是异步的，所以轮询等待落库。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatGodAgentLoopTest {

    private static final long WAIT_MILLIS = 8_000L;

    @Autowired
    private ChatService chatService;
    @Autowired
    private InterviewMessageRepository messageRepository;
    @Autowired
    private InterviewerRepository interviewerRepository;

    @MockBean
    private LlmClient llmClient;
    @MockBean
    private SkillAgentLlmClient skillAgentLlmClient;

    private void stubChat(String reply) {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept(reply);
            return null;
        }).when(llmClient).stream(anyList(), any(LlmOptions.class), any());
    }

    private List<InterviewMessage> waitUntilSettled(Long sessionId, int expectedMinSeq) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        List<InterviewMessage> messages = List.of();
        while (System.currentTimeMillis() < deadline) {
            messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            if (messages.size() >= expectedMinSeq) {
                Thread.sleep(300);
                return messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            }
            Thread.sleep(50);
        }
        return messages;
    }

    @Test
    void singleInterviewerRunsExactlyOneRound() throws Exception {
        stubChat("【平静】请做一下自我介绍。");

        Interviewer strict = interviewerRepository.findByCode("strict").orElseThrow();
        CreateSessionResponse created = chatService.createSession(
                new CreateSessionRequest("strict", "java", null));

        chatService.processMessage(created.sessionId(), "我叫候选人");
        // 自由会话（批次 4.1 起）不再落开场白：system + user + 1 条 assistant
        List<InterviewMessage> messages = waitUntilSettled(created.sessionId(), 3);

        verify(llmClient, times(1)).stream(anyList(), any(LlmOptions.class), any());
        long assistantCount = messages.stream()
                .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole())).count();
        // 建会话不再落开场白，本轮只落一条
        assertEquals(1, assistantCount, "单人场景一轮只能落一条 assistant 台词");
        assertEquals(strict.getId().intValue(),
                messages.get(messages.size() - 1).getSenderRoleId());
    }

    @Test
    void twoInterviewersHandOffUntilBudgetExhausted() throws Exception {
        stubChat("【平静】这个问题我来补充。");

        Interviewer strict = interviewerRepository.findByCode("strict").orElseThrow();
        Interviewer friendly = interviewerRepository.findByCode("friendly").orElseThrow();
        CreateSessionResponse created = chatService.createSession(
                new CreateSessionRequest("strict", "java", null));
        Long sessionId = created.sessionId();

        List<Integer> present = chatService.setPresentRoleIds(sessionId,
                List.of(strict.getId().intValue(), friendly.getId().intValue()));
        assertEquals(List.of(Math.min(strict.getId().intValue(), friendly.getId().intValue()),
                Math.max(strict.getId().intValue(), friendly.getId().intValue())), present);

        // 上帝 Agent 每次都选第二位面试官接话
        when(skillAgentLlmClient.completeStream(anyList(), anyList(), any(LlmOptions.class),
                any(), any(), any(AtomicBoolean.class)))
                .thenReturn(new SkillAgentLlmClient.Completion("", null,
                        List.of(new SkillAgentLlmClient.ToolCall("c1", SpeakerSelector.SELECT_NEXT_SPEAKER,
                                "{\"role_id\":" + friendly.getId() + ",\"reason\":\"她想追问\"}")),
                        "tool_calls", null));

        chatService.processMessage(sessionId, "我叫候选人");
        // 自由会话不再落开场白：system + user + 3 轮 assistant
        List<InterviewMessage> messages = waitUntilSettled(sessionId, 5);

        // 预算 3 轮：第 1 轮面试官 → 第 2/3 轮接话者 → 到顶收尾
        verify(llmClient, times(3)).stream(anyList(), any(LlmOptions.class), any());
        verify(skillAgentLlmClient, times(2)).completeStream(anyList(), anyList(), any(LlmOptions.class),
                any(), any(), any(AtomicBoolean.class));

        int friendlyId = friendly.getId().intValue();
        AtomicInteger friendlyLines = new AtomicInteger();
        messages.stream()
                .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole())
                        && m.getSenderRoleId() != null && m.getSenderRoleId() == friendlyId)
                .forEach(m -> friendlyLines.incrementAndGet());
        assertEquals(2, friendlyLines.get(), "两位面试官都说过话，且交还玩家前停在第 3 轮");
        assertTrue(messages.get(messages.size() - 1).getSenderRoleId() == friendlyId
                        || messages.get(messages.size() - 1).getSenderRoleId() == strict.getId().intValue());
    }
}

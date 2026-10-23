package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.SessionMessageView;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * {@code sender_role_id} 落库验收（对齐 {@code db/entities/line.rs}）：
 * {@code system} → null、{@code user} → 0、{@code assistant} → 当前面试官 id。
 *
 * <p>LLM 用替身，绝不真调网络；{@code processMessage} 是异步的，所以轮询等待落库。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatMessageSenderRoleTest {

    private static final long WAIT_MILLIS = 5_000L;

    @Autowired
    private ChatService chatService;
    @Autowired
    private InterviewMessageRepository messageRepository;
    @Autowired
    private InterviewerRepository interviewerRepository;

    @MockBean
    private LlmClient llmClient;

    @Test
    void persistsSenderRoleIdForSystemUserAndAssistant() throws Exception {
        doAnswer(invocation -> {
            // 自 B4 起 ChatService 显式传入生成参数：stream(messages, options, onDelta)
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("【平静】好的，我们开始。");
            return null;
        }).when(llmClient).stream(anyList(), any(LlmOptions.class), any());

        Interviewer interviewer = interviewerRepository.findByCode("strict").orElseThrow();
        int interviewerId = interviewer.getId().intValue();

        CreateSessionResponse created = chatService.createSession(
                new CreateSessionRequest("strict", "java", null));
        Long sessionId = created.sessionId();

        // 建会话已落两条：seq 0 = system 串，seq 1 = 开场白
        List<InterviewMessage> opening = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        assertEquals(InterviewMessage.ROLE_SYSTEM, opening.get(0).getRole());
        assertNull(opening.get(0).getSenderRoleId(), "system 消息的 sender_role_id 必须是 null");
        assertEquals(InterviewMessage.ROLE_ASSISTANT, opening.get(1).getRole());
        assertEquals(interviewerId, opening.get(1).getSenderRoleId(),
                "开场白是面试官说的，必须是 interviewer.id");

        // 发一条消息，等异步落库
        chatService.processMessage(sessionId, "我叫蔡智豪");

        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        List<InterviewMessage> messages;
        while (true) {
            messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            boolean userSaved = messages.stream().anyMatch(m -> InterviewMessage.ROLE_USER.equals(m.getRole())
                    && "我叫蔡智豪".equals(m.getContent()));
            long assistantCount = messages.stream()
                    .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole())).count();
            if (userSaved && assistantCount >= 2) {
                break;
            }
            assertTrue(System.currentTimeMillis() < deadline, "等待异步落库超时，当前消息数：" + messages.size());
            Thread.sleep(50);
        }

        InterviewMessage user = messages.stream()
                .filter(m -> InterviewMessage.ROLE_USER.equals(m.getRole())).findFirst().orElseThrow();
        assertEquals(0, user.getSenderRoleId(), "玩家说话：sender_role_id = 0");

        List<InterviewMessage> assistantLines = messages.stream()
                .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole()))
                .filter(m -> !m.getSeq().equals(1))
                .toList();
        assertTrue(!assistantLines.isEmpty(), "本轮至少要落一条 assistant 台词");
        assistantLines.forEach(m -> assertEquals(interviewerId, m.getSenderRoleId(),
                "面试官说话：sender_role_id = interviewer.id"));

        // 对外视图同样带出该字段（afterSeq=-1 把 seq=0 的 system 行也带回来）
        List<SessionMessageView> views = chatService.listMessages(sessionId, -1);
        assertTrue(views.stream().anyMatch(v -> Integer.valueOf(0).equals(v.senderRoleId())));
        assertTrue(views.stream().anyMatch(v -> Integer.valueOf(interviewerId).equals(v.senderRoleId())));
        assertTrue(views.stream().anyMatch(v -> v.senderRoleId() == null));
    }
}

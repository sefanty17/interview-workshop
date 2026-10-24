package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.SessionMessageView;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmOptions;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * 思考链落库验收（对齐 {@code line.thinking} + {@code generator/mod.rs:698-705}）：
 *
 * <ul>
 *   <li>本轮<b>最终句</b>挂完整思考链；</li>
 *   <li>非最终句的 {@code thinking} 为 {@code null}（思考链不进每句正文）；</li>
 *   <li>对外视图 {@link SessionMessageView#thinking()} 原样带出。</li>
 * </ul>
 *
 * <p>LLM 用替身：一次 {@code stream} 里先回调若干 reasoning 片段，再回调正文。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatThinkingChainTest {

    private static final long WAIT_MILLIS = 5_000L;

    /** 本轮思考链：两个片段按序追加（不以对方为前缀，走 append 分支）。 */
    private static final String THINKING_PART_1 = "先看看他说的技术栈，";
    private static final String THINKING_PART_2 = "Spring Boot 加 JPA，属于常规后端组合。";
    private static final String THINKING_FULL = THINKING_PART_1 + THINKING_PART_2;

    @Autowired
    private ChatService chatService;

    @Autowired
    private InterviewMessageRepository messageRepository;

    @MockBean
    private LlmClient llmClient;

    @Test
    void thinkingIsAttachedToFinalLineOnly() throws Exception {
        doAnswer(invocation -> {
            // stream(messages, options, onDelta)；思考链回调挂在 options.onReasoning()
            LlmOptions options = invocation.getArgument(1);
            Consumer<String> onDelta = invocation.getArgument(2);
            Consumer<String> onReasoning = options.onReasoning();
            assertNotNull(onReasoning, "ChatService 必须挂上思考链回调");
            onReasoning.accept(THINKING_PART_1);
            onReasoning.accept(THINKING_PART_2);
            // 三段台词 → 至少前两段是非最终句，最后一段是最终句
            onDelta.accept("【疑惑】你做过商城？");
            onDelta.accept("【平静】用什么做的持久层。");
            onDelta.accept("【自信】说具体点。");
            return null;
        }).when(llmClient).stream(anyList(), any(LlmOptions.class), any());

        CreateSessionResponse created = chatService.createSession(
                new CreateSessionRequest("strict", "java", null));
        Long sessionId = created.sessionId();

        chatService.processMessage(sessionId, "我做过一个在线商城");

        // SentenceSplitter 会按合并阈值把多句并成一段，落库条数不固定；
        // 判据是「本轮结束」——最终句一定带思考链，且除它以外没有别的行带。
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        List<InterviewMessage> messages;
        while (true) {
            messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            long assistantCount = messages.stream()
                    .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole())).count();
            boolean done = assistantCount >= 1 && messages.stream()
                    .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole()))
                    .anyMatch(m -> m.getThinking() != null);
            if (done) {
                break;
            }
            assertTrue(System.currentTimeMillis() < deadline,
                    "等待异步落库超时，当前消息数：" + messages.size());
            Thread.sleep(50);
        }

        List<InterviewMessage> assistantLines = messages.stream()
                .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole()))
                .toList();
        assertTrue(!assistantLines.isEmpty(), "本轮至少要落一条 assistant 台词");

        // 只有最后一句带思考链，前面每一句都是 null
        InterviewMessage last = assistantLines.get(assistantLines.size() - 1);
        assertEquals(THINKING_FULL, last.getThinking(), "最终句挂本轮完整思考链");
        assistantLines.subList(0, assistantLines.size() - 1)
                .forEach(m -> assertEquals(null, m.getThinking(), "非最终句不挂思考链"));

        // 对外视图原样带出
        List<SessionMessageView> views = chatService.listMessages(sessionId, -1);
        SessionMessageView lastView = views.stream()
                .filter(v -> InterviewMessage.ROLE_ASSISTANT.equals(v.role()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertNotNull(lastView.thinking(), "视图里最终句应带思考链");
        assertEquals(THINKING_FULL, lastView.thinking());
    }
}

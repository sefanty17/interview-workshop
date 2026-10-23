package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.SessionFinishView;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 结束评分验收（规格 S5 改动 1/3）：
 * {@code score = clamp(60 + min(25, 5*n) + min(15, avgLen/40), 0, 100)}。
 *
 * <p>建场次走 {@link ChatService}（自带 system + 开场白两条非 user 台词），
 * user 台词直接落库，避免依赖 LLM。
 */
@SpringBootTest
@ActiveProfiles("test")
class EngineFinishTest {

    @Autowired
    private InterviewEngine engine;
    @Autowired
    private ChatService chatService;
    @Autowired
    private InterviewSessionRepository sessionRepository;
    @Autowired
    private InterviewMessageRepository messageRepository;

    @MockBean
    private LlmClient llmClient;

    @Test
    void noUserMessageScoresBaseline60() {
        Long sessionId = newSession();

        SessionFinishView result = engine.finishNow(sessionId);

        assertEquals(60, result.score(), "无 user 台词时只有基础分");
        assertEquals(InterviewEngine.STATUS_FINISHED, result.status());
        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(60, session.getScore(), "分数必须落库");
        assertEquals(InterviewEngine.STATUS_FINISHED, session.getStatus());
    }

    @Test
    void threeUserMessagesOf200CharsScore80() {
        Long sessionId = newSession();
        saveUserMessages(sessionId, 3, 200);

        SessionFinishView result = engine.finishNow(sessionId);

        // 60 + min(25, 15) + min(15, 200/40 = 5) = 80
        assertEquals(80, result.score());
        assertEquals(80, sessionRepository.findById(sessionId).orElseThrow().getScore());
    }

    @Test
    void longAnswersCapAt100() {
        Long sessionId = newSession();
        // 5 条起条数分才满 25；配合长度分 15，封顶 100
        saveUserMessages(sessionId, 5, 10000);

        SessionFinishView result = engine.finishNow(sessionId);

        // 60 + min(25, 25) + min(15, 10000/40 = 250) → 100，clamp 后仍是 100
        assertEquals(100, result.score());
        assertEquals(100, sessionRepository.findById(sessionId).orElseThrow().getScore());
    }

    private Long newSession() {
        // F-1 起不再按岗位自动绑剧本：评分用例只要一个自由会话（不传 scriptCode）
        return chatService.createSession(new CreateSessionRequest("strict", "java", null)).sessionId();
    }

    /** 落 {@code count} 条 user 台词，每条 {@code length} 个字符。 */
    private void saveUserMessages(Long sessionId, int count, int length) {
        String content = "答".repeat(length);
        int seq = messageRepository.findMaxSeqBySessionId(sessionId);
        for (int i = 0; i < count; i++) {
            InterviewMessage message = new InterviewMessage();
            message.setSessionId(sessionId);
            message.setSeq(++seq);
            message.setRole(InterviewMessage.ROLE_USER);
            message.setContent(content);
            message.setMsgType(null); // 真实用户回答：msgType 为 null（"text" 等非空值不计分）
            message.setCreatedAt(LocalDateTime.now());
            messageRepository.save(message);
        }
    }
}

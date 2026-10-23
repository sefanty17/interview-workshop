package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.SessionFinishView;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 结束收尾验收（批次 4.2 后）：{@code finishNow} 不再算分，score 留空，
 * 结束报告分数由大模型评估管线后续接管。
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

    @MockBean
    private LlmClient llmClient;

    @Test
    void finishNowLeavesScoreNull() {
        Long sessionId = newSession();

        SessionFinishView result = engine.finishNow(sessionId);

        assertEquals(InterviewEngine.STATUS_FINISHED, result.status());
        assertNull(result.score(), "批次 4.2 起结束不再算分，score 必须为 null");
        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertNull(session.getScore(), "分数不能落库，更不能造 0 分假数据");
        assertEquals(InterviewEngine.STATUS_FINISHED, session.getStatus());
    }

    private Long newSession() {
        // F-1 起不再按岗位自动绑剧本：用例只要一个自由会话（不传 scriptCode）
        return chatService.createSession(new CreateSessionRequest("strict", "java", null)).sessionId();
    }
}

package cn.edu.zjut.interview.evaluation;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewEvaluationDimensionRepository;
import cn.edu.zjut.interview.repository.InterviewEvaluationRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.service.ChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 规格 E1 §5.3 的 3 条服务层断言：真落库（test profile 的内存 H2）+ LLM 用替身。
 *
 * <p>Spring Boot 3.3 用 {@code @MockBean}（{@code @MockitoBean} 是 3.4 才有的），
 * 与 {@code ChatGodAgentLoopTest} 保持同一写法。绝不真调网络。
 */
@SpringBootTest
@ActiveProfiles("test")
class EvaluationServiceTest {

    /** technical 90 / depth 80 / logic 70 / fit 60 → 27 + 20 + 17.5 + 12 = 76.5 → 77。 */
    private static final String LLM_JSON = """
            {
              "summary": "整体合格，深度可再加强",
              "strengths": ["技术点准确", "表达有层次"],
              "weaknesses": ["底层原理讲得浅"],
              "suggestions": ["补源码与调优"],
              "dimensions": {
                "technical": {"score": 90, "comment": "技术点准确"},
                "depth":     {"score": 80, "comment": "讲到了原理"},
                "logic":     {"score": 70, "comment": "偶有跳跃"},
                "fit":       {"score": 60, "comment": "部分偏题"}
              }
            }""";

    @Autowired
    private EvaluationService evaluationService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private InterviewSessionRepository sessionRepository;
    @Autowired
    private InterviewMessageRepository messageRepository;
    @Autowired
    private InterviewEvaluationRepository evaluationRepository;
    @Autowired
    private InterviewEvaluationDimensionRepository dimensionRepository;

    @MockBean
    private LlmClient llmClient;

    private final List<Long> sessionIds = new ArrayList<>();

    @AfterEach
    void 清理场次与评估() {
        for (Long sessionId : sessionIds) {
            // 用 deleteAll(实体) 而不是仓库里的 deleteBySessionId：后者是 @Modifying 的 JPQL，
            // 必须跑在事务里，而 @AfterEach 没有事务上下文。SimpleJpaRepository 自己的方法带事务。
            dimensionRepository.deleteAll(dimensionRepository.findBySessionIdOrderByIdAsc(sessionId));
            evaluationRepository.deleteAll(evaluationRepository.findBySessionId(sessionId));
            messageRepository.deleteAll(messageRepository.findBySessionIdOrderBySeqAsc(sessionId));
            sessionRepository.findById(sessionId).ifPresent(sessionRepository::delete);
        }
        sessionIds.clear();
    }

    /** 1. 评估成功：两表各落一份（1 + 4），且场次总分被回写。 */
    @Test
    void 评估成功落库且回写场次总分() {
        when(llmClient.complete(anyList())).thenReturn(LLM_JSON);
        Long sessionId = newSessionWithDialogue(2);

        var result = evaluationService.evaluate(sessionId);

        assertEquals(77, result.overallScore(), "90×0.30 + 80×0.25 + 70×0.25 + 60×0.20 = 76.5 → 77");
        assertEquals(4, result.dimensions().size());
        assertEquals(1, evaluationRepository.findBySessionId(sessionId).size());
        assertEquals(4, dimensionRepository.findBySessionIdOrderByIdAsc(sessionId).size());

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(77, session.getScore(), "score 必须回写到 interview_session");

        // 存进去的三组要点是 JSON 数组字符串，读回来要还原成数组
        var loaded = evaluationService.load(sessionId);
        assertEquals(List.of("技术点准确", "表达有层次"), loaded.strengths());
        assertEquals(List.of("底层原理讲得浅"), loaded.weaknesses());
        assertEquals(List.of("补源码与调优"), loaded.suggestions());
    }

    /** 2. 重跑覆盖：连调两次，两表条数不变（先删后插 + 唯一约束）。 */
    @Test
    void 重跑评估是覆盖而不是追加() {
        when(llmClient.complete(anyList())).thenReturn(LLM_JSON);
        Long sessionId = newSessionWithDialogue(2);

        evaluationService.evaluate(sessionId);
        evaluationService.evaluate(sessionId);

        assertEquals(1, evaluationRepository.findBySessionId(sessionId).size());
        assertEquals(4, dimensionRepository.findBySessionIdOrderByIdAsc(sessionId).size());
        assertEquals(77, sessionRepository.findById(sessionId).orElseThrow().getScore());
    }

    /** 3. 回答不足 2 条：报 EVAL_NO_ENOUGH_DIALOGUE，且不写出任何评估记录。 */
    @Test
    void 回答不足两条时报错且不落库() {
        when(llmClient.complete(anyList())).thenReturn(LLM_JSON);
        Long sessionId = newSessionWithDialogue(1);

        EvaluationException e = assertThrows(EvaluationException.class,
                () -> evaluationService.evaluate(sessionId));

        assertEquals(EvaluationException.NO_ENOUGH_DIALOGUE, e.code());
        assertTrue(evaluationRepository.findBySessionId(sessionId).isEmpty(),
                "失败路径不许留下评估记录，更不许是 0 分假记录");
        assertTrue(dimensionRepository.findBySessionIdOrderByIdAsc(sessionId).isEmpty());
        assertNull(sessionRepository.findById(sessionId).orElseThrow().getScore(),
                "评估失败不该动场次总分");
    }

    /** 建场次 + 写 n 条求职者回答（首尾各配一句面试官，system 行也写一条验证被排除）。 */
    private Long newSessionWithDialogue(int userCount) {
        Long sessionId = chatService.createSession(new CreateSessionRequest("strict", "java", null)).sessionId();
        sessionIds.add(sessionId);

        save(sessionId, 1, InterviewMessage.ROLE_SYSTEM, "你是面试官，请按剧本提问");
        int seq = 2;
        for (int i = 0; i < userCount; i++) {
            save(sessionId, seq++, InterviewMessage.ROLE_ASSISTANT, "第 " + (i + 1) + " 个问题");
            save(sessionId, seq++, InterviewMessage.ROLE_USER, "第 " + (i + 1) + " 个回答内容");
        }
        return sessionId;
    }

    private void save(Long sessionId, int seq, String role, String content) {
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(sessionId);
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content);
        messageRepository.save(message);
    }
}

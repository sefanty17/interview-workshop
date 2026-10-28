package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.InterviewMessage;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 规格 E1 §5.4 的 2 条接口断言：GET 没评估过 → 404；POST（LLM 用替身）→ 200 且四个维度齐全。
 *
 * <p>只验语义（状态码、总分、维度条数、camelCase 字段名），落库细节由
 * {@code EvaluationServiceTest} 覆盖，这里不重复。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EvaluationControllerTest {

    private static final String LLM_JSON = """
            {
              "summary": "基础合格",
              "strengths": ["答到了点上"],
              "weaknesses": ["不够深入"],
              "suggestions": ["多补原理"],
              "dimensions": {
                "technical": {"score": 80, "comment": "准确"},
                "depth":     {"score": 80, "comment": "一般"},
                "logic":     {"score": 80, "comment": "清楚"},
                "fit":       {"score": 80, "comment": "贴合"}
              }
            }""";

    @Autowired
    private MockMvc mockMvc;
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
            // 用 deleteAll(实体) 而不是 deleteBySessionId：后者是 @Modifying 的 JPQL，需要事务上下文
            dimensionRepository.deleteAll(dimensionRepository.findBySessionIdOrderByIdAsc(sessionId));
            evaluationRepository.deleteAll(evaluationRepository.findBySessionId(sessionId));
            messageRepository.deleteAll(messageRepository.findBySessionIdOrderBySeqAsc(sessionId));
            sessionRepository.findById(sessionId).ifPresent(sessionRepository::delete);
        }
        sessionIds.clear();
    }

    /** 1. 没评估过的场次，GET /evaluation 返回 404（不在这里现算）。 */
    @Test
    void 未评估的场次查询返回404() throws Exception {
        Long sessionId = newSessionWithTwoAnswers();

        mockMvc.perform(get("/api/sessions/" + sessionId + "/evaluation"))
                .andExpect(status().isNotFound());
    }

    /** 2. POST /evaluate → 200，总分与四个维度都齐，字段名是 camelCase。 */
    @Test
    void 触发评估返回总分与四个维度() throws Exception {
        when(llmClient.complete(anyList())).thenReturn(LLM_JSON);
        Long sessionId = newSessionWithTwoAnswers();

        mockMvc.perform(post("/api/sessions/" + sessionId + "/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.jobCode").value("java"))
                .andExpect(jsonPath("$.overallScore").value(80))
                .andExpect(jsonPath("$.dimensions.length()").value(4))
                .andExpect(jsonPath("$.dimensions[0].code").value("technical"))
                .andExpect(jsonPath("$.dimensions[0].label").value("技术正确性"))
                .andExpect(jsonPath("$.strengths[0]").value("答到了点上"))
                .andExpect(jsonPath("$.createdAt").exists());

        // 评估过之后 GET 就能取到（POST 与 GET 是同一份数据）
        mockMvc.perform(get("/api/sessions/" + sessionId + "/evaluation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(80))
                .andExpect(jsonPath("$.dimensions.length()").value(4));
    }

    private Long newSessionWithTwoAnswers() {
        Long sessionId = chatService.createSession(new CreateSessionRequest("strict", "java", null)).sessionId();
        sessionIds.add(sessionId);
        save(sessionId, 1, InterviewMessage.ROLE_ASSISTANT, "说说 JVM 内存模型");
        save(sessionId, 2, InterviewMessage.ROLE_USER, "堆和栈");
        save(sessionId, 3, InterviewMessage.ROLE_ASSISTANT, "说说 GC");
        save(sessionId, 4, InterviewMessage.ROLE_USER, "可达性分析");
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

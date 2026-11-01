package cn.edu.zjut.interview.summary;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewSummaryRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话总结服务的断言（issue #4）：真落库（test profile 的内存 H2）+ LLM 用替身。
 *
 * <p>与 {@code EvaluationServiceTest} 同一写法：{@code @MockBean} 替掉 {@link LlmClient}，
 * 绝不真调网络；场次由 {@code ChatService} 建，再手动补 {@code script_id}（剧本场次判据）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SummaryServiceTest {

    private static final String FINAL_JSON = """
            {
              "overview": "围绕 JVM 与并发聊了三轮，回答以背诵为主",
              "questions": [
                {"index": 1, "question": "JVM 内存分区", "points": ["堆和栈"],
                 "blockers": ["没说元空间"], "depth": "中"},
                {"index": 2, "question": "synchronized 锁升级", "points": [],
                 "blockers": ["只说了重量级锁"], "depth": "浅"}
              ],
              "coverage": {"covered": ["JVM 内存"], "missing": ["并发包"]},
              "vocabulary": {"good": ["新生代"], "improve": ["用「停顿时间」代替「很慢」"]}
            }""";

    private static final String CHUNK_JSON = """
            {
              "questions": [{"index": 1, "question": "片段里的提问", "points": [], "blockers": [], "depth": "中"}],
              "coverage": {"covered": [], "missing": []},
              "vocabulary": {"good": [], "improve": []}
            }""";

    @Autowired
    private SummaryService summaryService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private InterviewSessionRepository sessionRepository;
    @Autowired
    private InterviewMessageRepository messageRepository;
    @Autowired
    private InterviewSummaryRepository summaryRepository;

    @MockBean
    private LlmClient llmClient;

    private final List<Long> sessionIds = new ArrayList<>();

    @AfterEach
    void 清理场次与总结() {
        for (Long sessionId : sessionIds) {
            summaryRepository.deleteAll(summaryRepository.findBySessionId(sessionId));
            messageRepository.deleteAll(messageRepository.findBySessionIdOrderBySeqAsc(sessionId));
            sessionRepository.findById(sessionId).ifPresent(sessionRepository::delete);
        }
        sessionIds.clear();
    }

    /** 1. 剧本场次：一次调用生成完整摘要并落库，load 能读回结构化字段。 */
    @Test
    void 剧本场次生成摘要并落库() {
        when(llmClient.complete(anyList())).thenReturn(FINAL_JSON);
        Long sessionId = newSession(3, true, 0);

        var result = summaryService.generate(sessionId);

        assertEquals("ok", result.status());
        assertEquals("围绕 JVM 与并发聊了三轮，回答以背诵为主", result.overview());
        assertEquals(2, result.questions().size());
        assertEquals(List.of("JVM 内存"), result.coverage().covered());
        assertEquals(List.of("并发包"), result.coverage().missing());
        assertEquals(List.of("新生代"), result.vocabulary().good());
        assertEquals(1, summaryRepository.findBySessionId(sessionId).size());

        var loaded = summaryService.load(sessionId);
        assertEquals("中", loaded.questions().get(0).depth());
        assertEquals(List.of("没说元空间"), loaded.questions().get(0).blockers());
    }

    /** 2. 自由对话场次：按规格不生成总结，抛 NOT_SCRIPTED 且不落库。 */
    @Test
    void 自由对话场次不生成总结() {
        when(llmClient.complete(anyList())).thenReturn(FINAL_JSON);
        Long sessionId = newSession(2, false, 0);

        SummaryException e = assertThrows(SummaryException.class, () -> summaryService.generate(sessionId));

        assertEquals(SummaryException.NOT_SCRIPTED, e.code());
        assertTrue(summaryRepository.findBySessionId(sessionId).isEmpty(), "自由对话不写任何总结行");
    }

    /** 3. 模型输出不合法：落一行 failed（报告页要能显示「摘要生成失败」），并抛出带 code 的异常。 */
    @Test
    void 模型输出不合法时落失败记录() {
        when(llmClient.complete(anyList())).thenReturn("抱歉，我无法总结。");
        Long sessionId = newSession(2, true, 0);

        SummaryException e = assertThrows(SummaryException.class, () -> summaryService.generate(sessionId));

        assertEquals(SummaryException.BAD_JSON, e.code());
        var saved = summaryRepository.findBySessionId(sessionId);
        assertEquals(1, saved.size());
        assertEquals("failed", saved.get(0).getStatus());
        assertTrue(saved.get(0).getError().contains("JSON"));

        var loaded = summaryService.load(sessionId);
        assertEquals("failed", loaded.status());
        assertTrue(loaded.questions().isEmpty());
    }

    /** 4. 长对话：先按片段抽取、再合并，调用次数 = 段数 + 1。 */
    @Test
    void 长对话先分块再合并() {
        when(llmClient.complete(anyList()))
                .thenReturn(CHUNK_JSON, CHUNK_JSON, CHUNK_JSON, FINAL_JSON);
        // 12 轮 × (1200 字答案 + 问题) ≈ 14600 字，按 6000 字一段 → 3 段
        Long sessionId = newSession(12, true, 1200);

        var result = summaryService.generate(sessionId);

        assertEquals("ok", result.status());
        assertEquals(2, result.questions().size());
        verify(llmClient, times(4)).complete(anyList());
    }

    /**
     * 5. 只有旁白 / 剧情提示（借用 role=user 落库）的场次：不能算作「求职者发言」。
     *
     * <p>引擎把旁白与剧情提示借 user 角色落库，如果直接按 role 计数，一场还没开口的面试
     * 会被当成「已经答过一轮」，总结里就会出现把剧情指令当回答的荒谬摘要。
     */
    @Test
    void 旁白与剧情提示不算求职者发言() {
        when(llmClient.complete(anyList())).thenReturn(FINAL_JSON);
        Long sessionId = newSession(0, true, 0);

        save(sessionId, 2, InterviewMessage.ROLE_USER, "{旁白: 面试开始。录音与计时已开启。}");
        save(sessionId, 3, InterviewMessage.ROLE_USER,
                "{旁白: （接下来的剧情演绎提示：请候选人做一分钟自我介绍。）}");

        SummaryException e = assertThrows(SummaryException.class, () -> summaryService.generate(sessionId));

        assertEquals(SummaryException.NO_ENOUGH_DIALOGUE, e.code());
        assertTrue(summaryRepository.findBySessionId(sessionId).isEmpty(), "没有求职者发言就不该生成总结");
    }

    /**
     * 建场次并写入 n 轮问答。
     *
     * @param turns    问答轮数
     * @param scripted true = 剧本场次（补 {@code script_id}），false = 自由对话
     * @param filler   每句答案额外补多少字（用来把对话撑过分段阈值）
     */
    private Long newSession(int turns, boolean scripted, int filler) {
        Long sessionId = chatService.createSession(new CreateSessionRequest("strict", "java", null)).sessionId();
        sessionIds.add(sessionId);
        if (scripted) {
            InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
            session.setScriptId(1L);
            sessionRepository.save(session);
        }

        int seq = 1;
        save(sessionId, seq++, InterviewMessage.ROLE_SYSTEM, "你是面试官，请按剧本提问");
        for (int i = 0; i < turns; i++) {
            save(sessionId, seq++, InterviewMessage.ROLE_ASSISTANT, "第 " + (i + 1) + " 个问题");
            save(sessionId, seq++, InterviewMessage.ROLE_USER,
                    "第 " + (i + 1) + " 个回答" + "甲".repeat(Math.max(0, filler)));
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

package cn.edu.zjut.interview.report;

import cn.edu.zjut.interview.domain.InterviewEvaluation;
import cn.edu.zjut.interview.domain.InterviewEvaluationDimension;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.KnowledgeHit;
import cn.edu.zjut.interview.dto.TrendView;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.knowledge.RetrievalService;
import cn.edu.zjut.interview.repository.InterviewEvaluationDimensionRepository;
import cn.edu.zjut.interview.repository.InterviewEvaluationRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 成长曲线的三条核心口径：只收剧本场次、按时间升序、短板取最近窗口的平均分。
 *
 * <<p>检索用替身：本测试只验证「推荐哪个维度、用什么查询串」，语料命中是
 * {@code RetrievalServiceTest} 的职责，这里真跑索引反而依赖种子数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class TrendServiceTest {

    @Autowired
    private TrendService trendService;
    @Autowired
    private InterviewSessionRepository sessionRepository;
    @Autowired
    private InterviewEvaluationRepository evaluationRepository;
    @Autowired
    private InterviewEvaluationDimensionRepository dimensionRepository;

    @MockBean
    private RetrievalService retrievalService;

    private final List<Long> sessionIds = new ArrayList<>();

    @AfterEach
    void 清理() {
        for (Long sessionId : sessionIds) {
            dimensionRepository.deleteAll(dimensionRepository.findBySessionIdOrderByIdAsc(sessionId));
            evaluationRepository.deleteAll(evaluationRepository.findBySessionId(sessionId));
            sessionRepository.findById(sessionId).ifPresent(sessionRepository::delete);
        }
        sessionIds.clear();
    }

    /** 自由对话（scriptId 为空）不进趋势；点的顺序按结束时间升序。 */
    @Test
    void 只收剧本场次并按时间升序() {
        Long free = newSession(null, LocalDateTime.of(2026, 10, 1, 9, 0));
        Long first = newSession(1L, LocalDateTime.of(2026, 10, 2, 9, 0));
        Long second = newSession(1L, LocalDateTime.of(2026, 10, 3, 9, 0));
        newEvaluation(free, "java", 60, "自由对话", dims(60, 60, 60, 60));
        newEvaluation(first, "java", 70, "第一场", dims(70, 70, 70, 70));
        newEvaluation(second, "java", 80, "第二场", dims(80, 80, 80, 80));

        TrendView view = trendService.trend(null);

        assertEquals(TrendService.REQUIRED_POINTS, view.requiredPoints());
        assertEquals(2, view.points().size(), "自由对话场次不该进趋势");
        assertEquals(List.of(first, second), view.points().stream().map(p -> p.sessionId()).toList());
        assertEquals(List.of(70, 80), view.points().stream().map(p -> p.overallScore()).toList());
    }

    /** 最近窗口里平均分最低的两个维度进推荐，查询串优先用最新一条评语。 */
    @Test
    void 短板取最近几场平均最低的两个维度() {
        String hitText = "Spring 事务传播级别 REQUIRED / REQUIRES_NEW 的区别";
        when(retrievalService.search(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(new KnowledgeHit(1, 2, "spring-core", "Spring", "事务",
                        hitText, 0.5, 0.4, 3.2)));

        // fit 平均 54、logic 平均 64、depth 74、technical 84 → 短板是 fit / logic
        Long s1 = newSession(1L, LocalDateTime.of(2026, 10, 1, 9, 0));
        newEvaluation(s1, "java", 65, "第一场", dims(84, 74, 60, 50));
        Long s2 = newSession(1L, LocalDateTime.of(2026, 10, 2, 9, 0));
        newEvaluation(s2, "java", 70, "第二场", dims(84, 74, 68, 58));

        TrendView view = trendService.trend(null);
        assertEquals(2, view.recommendations().size());

        TrendView.Recommendation fit = view.recommendations().get(0);
        assertEquals("fit", fit.dimCode());
        assertEquals("岗位匹配度", fit.label());
        assertEquals(54, fit.averageScore());
        // 最新一条（第二场）的评语作为查询串
        assertEquals("岗位匹配度-2", fit.query());

        TrendView.Recommendation logic = view.recommendations().get(1);
        assertEquals("logic", logic.dimCode());
        assertEquals(64, logic.averageScore());

        assertTrue(fit.hits().size() == 1 && logic.hits().size() == 1, "替身命中的片段要原样带出");
    }

    private Long newSession(Long scriptId, LocalDateTime endedAt) {
        InterviewSession session = new InterviewSession();
        session.setTitle("面试 " + endedAt.toLocalDate());
        session.setUserId(1L);
        session.setInterviewerId(1L);
        session.setScriptId(scriptId);
        session.setStatus(InterviewEngine.STATUS_FINISHED);
        session.setJobCode("java");
        session.setPreview(Boolean.FALSE);
        session.setStartedAt(endedAt.minusMinutes(20));
        session.setEndedAt(endedAt);
        session.setCreatedAt(endedAt.minusMinutes(30));
        sessionRepository.save(session);
        sessionIds.add(session.getId());
        return session.getId();
    }

    private void newEvaluation(Long sessionId, String jobCode, int overall, String summary,
                               List<Integer> dimensionScores) {
        InterviewEvaluation evaluation = new InterviewEvaluation();
        evaluation.setSessionId(sessionId);
        evaluation.setJobCode(jobCode);
        evaluation.setOverallScore(overall);
        evaluation.setSummary(summary);
        evaluation.setStrengths("[]");
        evaluation.setWeaknesses("[]");
        evaluation.setSuggestions("[]");
        evaluation.setModel("deepseek-chat");
        evaluation.setCreatedAt(LocalDateTime.now());
        evaluationRepository.save(evaluation);

        String[] codes = {"technical", "depth", "logic", "fit"};
        String[] labels = {"技术正确性", "知识深度", "逻辑严谨性", "岗位匹配度"};
        for (int i = 0; i < codes.length; i++) {
            InterviewEvaluationDimension row = new InterviewEvaluationDimension();
            row.setSessionId(sessionId);
            row.setDimCode(codes[i]);
            row.setScore(dimensionScores.get(i));
            // 最新评语要能分辨：维度名-第几场（场次序号由分数顺序区分，这里统一用 2 表示第二场）
            row.setComment(labels[i] + "-2");
            dimensionRepository.save(row);
        }
    }

    private static List<Integer> dims(int technical, int depth, int logic, int fit) {
        return List.of(technical, depth, logic, fit);
    }
}

package cn.edu.zjut.interview.report;

import cn.edu.zjut.interview.domain.InterviewEvaluation;
import cn.edu.zjut.interview.domain.InterviewEvaluationDimension;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.EvaluationResult.DimensionScore;
import cn.edu.zjut.interview.dto.KnowledgeHit;
import cn.edu.zjut.interview.dto.TrendView;
import cn.edu.zjut.interview.dto.TrendView.Recommendation;
import cn.edu.zjut.interview.dto.TrendView.TrendPoint;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.evaluation.EvaluationDimension;
import cn.edu.zjut.interview.knowledge.RetrievalService;
import cn.edu.zjut.interview.repository.InterviewEvaluationDimensionRepository;
import cn.edu.zjut.interview.repository.InterviewEvaluationRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成长曲线与提升路径（<b>本项目扩展（课设指标 ⑥）</b>）。
 *
 * <p>数据只有两处：{@code interview_session}（剧本场次、结束时间、总分）与
 * {@code interview_evaluation_dimension}（四维度分）。不调大模型——趋势是既成事实的汇总，
 * 再调一次模型只会让同一个数字每次刷新都变。
 *
 * <p><b>只收剧本场次</b>：自由对话 {@code script_id} 为空、也不产生评估，
 * 混进来会把一条没有分数的空点插进曲线，让趋势失真。预览（试玩）场次同理排除。
 *
 * <p>提升路径按「最近几场里平均分最低的两个维度」找知识库条目。查询串优先用该维度的
 * 模型评语（里面有具体短板，如「Spring 事务传播级别没区分」），评语为空才退回维度名——
 * 这一步是让推荐落在用户真实缺陷上，而不是笼统的「加强基础」。
 */
@Service
public class TrendService {

    private static final Logger log = LoggerFactory.getLogger(TrendService.class);

    /** 画折线所需的最少场次：两个点只有一条线段，看不出起伏，三次才谈得上趋势。 */
    public static final int REQUIRED_POINTS = 3;

    /** 短板取最近这么多场，越久远的表现越不该影响「现在该补什么」。 */
    private static final int RECENT_WINDOW = 5;

    /** 只推荐两个短板，多了用户不会看。 */
    private static final int RECOMMEND_COUNT = 2;

    /** 每个短板推荐几条知识片段。 */
    private static final int TOP_K = 3;

    /** 评语当查询串时截断长度：BM25 对超长查询会命中过多词，排序反而更散。 */
    private static final int MAX_QUERY_LENGTH = 120;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InterviewSessionRepository sessionRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final InterviewEvaluationDimensionRepository dimensionRepository;
    private final RetrievalService retrievalService;

    public TrendService(InterviewSessionRepository sessionRepository,
                        InterviewEvaluationRepository evaluationRepository,
                        InterviewEvaluationDimensionRepository dimensionRepository,
                        RetrievalService retrievalService) {
        this.sessionRepository = sessionRepository;
        this.evaluationRepository = evaluationRepository;
        this.dimensionRepository = dimensionRepository;
        this.retrievalService = retrievalService;
    }

    /**
     * 趋势 + 推荐。
     *
     * @param userId 为空表示不按用户过滤（本项目当前是单用户，前端不传）
     */
    @Transactional(readOnly = true)
    public TrendView trend(Long userId) {
        List<InterviewSession> sessions = sessionRepository
                .findByStatusAndPreviewFalseOrderByCreatedAtDesc(InterviewEngine.STATUS_FINISHED)
                .stream()
                .filter(session -> session.getScriptId() != null)
                .filter(session -> userId == null || userId.equals(session.getUserId()))
                .sorted(Comparator.comparing(this::timeline))
                .toList();
        if (sessions.isEmpty()) {
            return new TrendView(REQUIRED_POINTS, List.of(), List.of());
        }

        List<Long> sessionIds = new ArrayList<>(sessions.size());
        for (InterviewSession session : sessions) {
            sessionIds.add(session.getId());
        }
        Map<Long, InterviewEvaluation> evaluationBySession = new HashMap<>();
        for (InterviewEvaluation evaluation : evaluationRepository.findBySessionIdIn(sessionIds)) {
            evaluationBySession.put(evaluation.getSessionId(), evaluation);
        }
        Map<Long, List<InterviewEvaluationDimension>> dimensionsBySession =
                groupBySession(dimensionRepository.findBySessionIdInOrderByIdAsc(sessionIds));

        List<TrendPoint> points = new ArrayList<>(sessions.size());
        for (InterviewSession session : sessions) {
            InterviewEvaluation evaluation = evaluationBySession.get(session.getId());
            // 还没评估的场次没有分数，画上去就是一个 0 分点，比不画更误导
            if (evaluation == null || evaluation.getOverallScore() == null) {
                continue;
            }
            points.add(new TrendPoint(
                    session.getId(),
                    DATE_FORMAT.format(timeline(session)),
                    session.getTitle(),
                    evaluation.getJobCode() == null ? session.getJobCode() : evaluation.getJobCode(),
                    evaluation.getOverallScore(),
                    toScores(dimensionsBySession.getOrDefault(session.getId(), List.of()))));
        }
        return new TrendView(REQUIRED_POINTS, List.copyOf(points), recommend(points));
    }

    /** 取曲线上的时间：优先结束时间，其次开始时间，最后创建时间；全空时排最前而不是抛错。 */
    private LocalDateTime timeline(InterviewSession session) {
        if (session.getEndedAt() != null) {
            return session.getEndedAt();
        }
        if (session.getStartedAt() != null) {
            return session.getStartedAt();
        }
        return session.getCreatedAt() == null ? LocalDateTime.MIN : session.getCreatedAt();
    }

    private static Map<Long, List<InterviewEvaluationDimension>> groupBySession(
            List<InterviewEvaluationDimension> rows) {
        Map<Long, List<InterviewEvaluationDimension>> grouped = new LinkedHashMap<>();
        for (InterviewEvaluationDimension row : rows) {
            grouped.computeIfAbsent(row.getSessionId(), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * 维度行 → 展示对象。
     *
     * <p>不直接用 {@link EvaluationDimension#of}：它会为未知 code 抛异常，而历史脏数据
     * 不该让整条成长曲线 500——认不出的维度跳过即可。
     */
    private static List<DimensionScore> toScores(List<InterviewEvaluationDimension> rows) {
        Map<String, String> labelByCode = new HashMap<>();
        for (EvaluationDimension dimension : EvaluationDimension.all()) {
            labelByCode.put(dimension.code(), dimension.label());
        }
        List<DimensionScore> scores = new ArrayList<>(rows.size());
        for (InterviewEvaluationDimension row : rows) {
            String label = labelByCode.get(row.getDimCode());
            if (label == null) {
                log.warn("[Trend] 跳过未知维度 {}", row.getDimCode());
                continue;
            }
            scores.add(new DimensionScore(row.getDimCode(), label,
                    row.getScore() == null ? 0 : row.getScore(),
                    row.getComment() == null ? "" : row.getComment()));
        }
        return List.copyOf(scores);
    }

    /**
     * 按最近几场的短板维度生成推荐。
     *
     * <p>平均分用「最近窗口内该维度有分的场次」算，缺维度的场次不拉低平均；
     * 评语取窗口内最新的那条，因为它描述的是当前状态。
     */
    private List<Recommendation> recommend(List<TrendPoint> points) {
        if (points.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, points.size() - RECENT_WINDOW);
        List<TrendPoint> recent = points.subList(from, points.size());

        Map<String, Integer> total = new HashMap<>();
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> latestComment = new HashMap<>();
        Map<String, String> labelByCode = new HashMap<>();
        for (TrendPoint point : recent) {
            for (DimensionScore dimension : point.dimensions()) {
                total.merge(dimension.code(), dimension.score(), Integer::sum);
                count.merge(dimension.code(), 1, Integer::sum);
                labelByCode.put(dimension.code(), dimension.label());
                if (dimension.comment() != null && !dimension.comment().isBlank()) {
                    // points 升序，后写覆盖先写 = 最新评语
                    latestComment.put(dimension.code(), dimension.comment().strip());
                }
            }
        }
        if (total.isEmpty()) {
            return List.of();
        }

        List<String> weakest = new ArrayList<>(total.keySet());
        weakest.sort(Comparator.comparingDouble(code -> average(total, count, code)));
        String jobCode = points.get(points.size() - 1).jobCode();

        List<Recommendation> recommendations = new ArrayList<>(RECOMMEND_COUNT);
        for (int i = 0; i < weakest.size() && i < RECOMMEND_COUNT; i++) {
            String code = weakest.get(i);
            String label = labelByCode.getOrDefault(code, code);
            int average = (int) Math.round(average(total, count, code));
            String query = latestComment.getOrDefault(code, label);
            if (query.length() > MAX_QUERY_LENGTH) {
                query = query.substring(0, MAX_QUERY_LENGTH);
            }
            List<KnowledgeHit> hits = retrievalService.search(query, null, jobCode,
                    RetrievalService.SearchOptions.defaults().withTopK(TOP_K),
                    RetrievalService.RetrievalMode.HYBRID);
            recommendations.add(new Recommendation(code, label, average, query, hits));
        }
        return List.copyOf(recommendations);
    }

    private static double average(Map<String, Integer> total, Map<String, Integer> count, String code) {
        int n = count.getOrDefault(code, 0);
        return n == 0 ? 0.0 : (double) total.getOrDefault(code, 0) / n;
    }
}

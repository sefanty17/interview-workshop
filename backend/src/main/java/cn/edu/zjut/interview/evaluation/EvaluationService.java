package cn.edu.zjut.interview.evaluation;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.domain.InterviewEvaluation;
import cn.edu.zjut.interview.domain.InterviewEvaluationDimension;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.EvaluationResult;
import cn.edu.zjut.interview.dto.EvaluationResult.DimensionScore;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewEvaluationDimensionRepository;
import cn.edu.zjut.interview.repository.InterviewEvaluationRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 多维内容评估的编排（<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>与 {@code /finish} 完全解耦：{@code InterviewEngine.finishNow} 一行都不动，
 * 评估由报告页单独触发。理由是一次评估要十几秒，「结束面试」不能卡在这上面。
 *
 * <p>整个 {@link #evaluate} 在一个事务里：LLM 失败直接抛出去回滚，
 * 绝不写出半份评估或 0 分假记录——假的 0 分会被当成真实评估写进报告。
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** 至少要这么多次求职者回答才评估：一问一答说明不了深度，评出来只是噪声。 */
    private static final int MIN_USER_MESSAGES = 2;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final InterviewEvaluationDimensionRepository dimensionRepository;
    private final LlmClient llmClient;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public EvaluationService(InterviewSessionRepository sessionRepository,
                             InterviewMessageRepository messageRepository,
                             InterviewEvaluationRepository evaluationRepository,
                             InterviewEvaluationDimensionRepository dimensionRepository,
                             LlmClient llmClient,
                             ConfigService configService,
                             ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.evaluationRepository = evaluationRepository;
        this.dimensionRepository = dimensionRepository;
        this.llmClient = llmClient;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /**
     * 对一场面试跑（或重跑）评估：组 prompt → 调大模型 → 解析 → 落库 → 回写场次总分。
     *
     * <p>重跑是覆盖而不是追加：先 {@code deleteBySessionId} 两张表再插新的，
     * 配合 {@code uk_evaluation_session} 唯一约束保证一场只有一份。
     *
     * @throws ResponseStatusException 场次不存在（404）
     * @throws EvaluationException     对话不足 / 模型输出不合法 / 大模型调用失败
     */
    @Transactional
    public EvaluationResult evaluate(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到场次：" + sessionId));

        // system 行是给模型的内部指令，不是面试内容，必须排除——把它当对话会污染评估口径
        List<InterviewMessage> dialogue = new ArrayList<>();
        int userCount = 0;
        for (InterviewMessage message : messageRepository.findBySessionIdOrderBySeqAsc(sessionId)) {
            if (InterviewMessage.ROLE_SYSTEM.equals(message.getRole())) {
                continue;
            }
            if (InterviewMessage.ROLE_USER.equals(message.getRole())
                    || InterviewMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                dialogue.add(message);
                if (InterviewMessage.ROLE_USER.equals(message.getRole())) {
                    userCount++;
                }
            }
        }
        if (userCount < MIN_USER_MESSAGES) {
            throw new EvaluationException(EvaluationException.NO_ENOUGH_DIALOGUE,
                    "至少需要 " + MIN_USER_MESSAGES + " 次回答才能评估，当前只有 " + userCount + " 次");
        }

        String model = configService.get(ConfigKey.LLM_MODEL);
        String raw;
        try {
            raw = llmClient.complete(EvaluationPrompter.build(session.getJobCode(), dialogue));
        } catch (LlmClient.LlmException e) {
            // 网络 / 超时 / 上游非 200 都在这里转成带 code 的异常：
            // 事务随即回滚，不会留下「删了旧的、没插新的」这种半途状态
            throw new EvaluationException(EvaluationException.LLM_FAILED,
                    "大模型评估失败：" + e.getMessage(), HttpStatus.BAD_GATEWAY, e);
        }

        EvaluationParser.EvaluationDraft draft = EvaluationParser.parse(raw);

        // 先删旧的再插新的：唯一约束 + 这两条 DELETE 保证一场只有一份评估
        dimensionRepository.deleteBySessionId(sessionId);
        evaluationRepository.deleteBySessionId(sessionId);
        dimensionRepository.flush();
        evaluationRepository.flush();

        LocalDateTime now = LocalDateTime.now();
        InterviewEvaluation evaluation = new InterviewEvaluation();
        evaluation.setSessionId(sessionId);
        evaluation.setJobCode(session.getJobCode());
        evaluation.setOverallScore(draft.overall());
        evaluation.setSummary(draft.summary());
        evaluation.setStrengths(toJson(draft.strengths()));
        evaluation.setWeaknesses(toJson(draft.weaknesses()));
        evaluation.setSuggestions(toJson(draft.suggestions()));
        evaluation.setModel(model);
        evaluation.setCreatedAt(now);
        evaluationRepository.save(evaluation);

        List<DimensionScore> dimensions = new ArrayList<>();
        for (EvaluationDimension dimension : EvaluationDimension.all()) {
            EvaluationParser.DimScore score = draft.dimensions().get(dimension.code());
            InterviewEvaluationDimension row = new InterviewEvaluationDimension();
            row.setSessionId(sessionId);
            row.setDimCode(dimension.code());
            row.setScore(score.score());
            row.setComment(score.comment());
            dimensionRepository.save(row);
            dimensions.add(new DimensionScore(dimension.code(), dimension.label(), score.score(), score.comment()));
        }

        // 回写场次总分：报告页与场次列表都从 interview_session.score 读，不另开一列
        session.setScore(draft.overall());
        sessionRepository.save(session);

        log.info("[EvaluationService] 场次 {} 评估完成，总分 {}（model={}）", sessionId, draft.overall(), model);
        return new EvaluationResult(sessionId, session.getJobCode(), draft.overall(), draft.summary(),
                draft.strengths(), draft.weaknesses(), draft.suggestions(), List.copyOf(dimensions), model, now);
    }

    /**
     * 取已有的评估；没有 → 404。
     *
     * <p>不在这里触发评估：一次评估十几秒，GET 语义必须是「查」，
     * 「没有就现算」会让前端一次点击挂起十几秒、且重试行为不可控。
     */
    @Transactional(readOnly = true)
    public EvaluationResult load(Long sessionId) {
        List<InterviewEvaluation> rows = evaluationRepository.findBySessionId(sessionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "场次 " + sessionId + " 还没有评估结果");
        }
        InterviewEvaluation row = rows.get(0);
        List<DimensionScore> dimensions = new ArrayList<>();
        for (InterviewEvaluationDimension dim : dimensionRepository.findBySessionIdOrderByIdAsc(sessionId)) {
            EvaluationDimension dimension = EvaluationDimension.of(dim.getDimCode());
            dimensions.add(new DimensionScore(dimension.code(), dimension.label(),
                    dim.getScore() == null ? 0 : dim.getScore(),
                    dim.getComment() == null ? "" : dim.getComment()));
        }
        return new EvaluationResult(row.getSessionId(), row.getJobCode(), row.getOverallScore(), row.getSummary(),
                fromJson(row.getStrengths()), fromJson(row.getWeaknesses()), fromJson(row.getSuggestions()),
                List.copyOf(dimensions), row.getModel(), row.getCreatedAt());
    }

    /**
     * 数组 → JSON 字符串（数组一律 JSON 存 CLOB，与 {@code interview_session.kps} 同一做法）。
     *
     * <p>走 {@code valueToTree} 而不是 {@code writeValueAsString}：后者抛受检的
     * {@code JsonProcessingException}，而这里装的是纯字符串列表，不可能真的失败，
     * 为一个不会发生的异常写 try-catch 反而把正常路径包进异常处理里。
     */
    private String toJson(List<String> values) {
        return objectMapper.valueToTree(values == null ? List.of() : values).toString();
    }

    /** JSON 字符串 → 数组；历史脏数据（不是数组）降级成空数组，不该让「查评估」挂掉。 */
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                values.add(item.asText());
            }
            return values;
        } catch (IOException e) {
            log.warn("[EvaluationService] 评估字段不是合法 JSON 数组，按空处理：{}", json);
            return List.of();
        }
    }
}

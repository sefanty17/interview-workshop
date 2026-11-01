package cn.edu.zjut.interview.summary;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.InterviewSummary;
import cn.edu.zjut.interview.dto.SummaryResult;
import cn.edu.zjut.interview.dto.SummaryResult.Coverage;
import cn.edu.zjut.interview.dto.SummaryResult.Vocabulary;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 对话总结 agent 的编排（<b>本项目扩展（issue #4）</b>）。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>只对剧本场次生成</b>：自由对话没有章节目标，没有「该覆盖哪些知识点」的口径，
 *       总结出来只是把对话复述一遍，反而增加报告噪音。</li>
 *   <li><b>结束面试不等待总结</b>：{@code /finish} 只负责把场次落库，总结在后台线程生成，
 *       失败也只写一行 failed 记录供报告页展示，绝不让「结束面试」失败或变慢。</li>
 *   <li><b>长对话先分块再合并</b>：一次性喂整场长对话时模型会漏掉中间几轮；
 *       分块抽取事实再合并，能把细节保住（见 {@link SummaryPrompter#split}）。</li>
 * </ol>
 *
 * <p>与 {@code EvaluationService} 一样不碰 {@code InterviewEngine}：两者都是场次结束后的独立产物。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    /** 只用来序列化摘要草稿（无状态、线程安全）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 至少一次求职者发言才有可总结的内容。 */
    private static final int MIN_USER_MESSAGES = 1;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewSummaryRepository summaryRepository;
    private final LlmClient llmClient;
    private final ConfigService configService;

    /**
     * 正在生成中的场次集合。
     *
     * <p>结束面试时的后台生成与报告页的手动重试可能撞在一起，两次「先删后插」会踩唯一约束。
     * 用内存集合挡一层：同一场次同一时刻只允许一个生成任务。
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 单线程足够：总结是低频后台任务，串行还能避免并发打满上游大模型。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "summary-agent");
            thread.setDaemon(true);
            return thread;
        }
    });

    public SummaryService(InterviewSessionRepository sessionRepository,
                          InterviewMessageRepository messageRepository,
                          InterviewSummaryRepository summaryRepository,
                          LlmClient llmClient,
                          ConfigService configService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
        this.llmClient = llmClient;
        this.configService = configService;
    }

    /**
     * 结束面试后的入口：后台生成，不阻塞调用方。
     *
     * <p>非剧本场次 / 试玩场次直接跳过（不落 failed 行），避免面试记录页出现无意义的
     * 「摘要生成失败」。真正的失败（模型挂了 / 输出坏了）会由 {@link #generate} 落库。
     */
    public void generateAsync(Long sessionId) {
        executor.submit(() -> {
            try {
                InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
                if (session == null) {
                    log.warn("[Summary] 场次 {} 不存在，跳过总结", sessionId);
                    return;
                }
                if (session.getScriptId() == null) {
                    log.debug("[Summary] 场次 {} 是自由对话，按规格不生成总结", sessionId);
                    return;
                }
                if (Boolean.TRUE.equals(session.isPreview())) {
                    log.debug("[Summary] 场次 {} 是试玩场次，不生成总结", sessionId);
                    return;
                }
                generate(sessionId);
            } catch (Exception e) {
                // 失败已由 generate 落成 failed 行；这里只记日志，绝不影响已经结束的场次
                log.warn("[Summary] 场次 {} 后台总结失败：{}", sessionId, e.toString());
            }
        });
    }

    /**
     * 同步生成（报告页重试用）。成功覆盖旧记录，失败也落一行 failed 供报告页展示。
     *
     * @throws SummaryException       对话不足 / 非剧本场次 / 模型输出不合法 / 大模型调用失败
     * @throws ResponseStatusException 场次不存在（404）
     */
    public SummaryResult generate(Long sessionId) {
        if (!inFlight.add(sessionId)) {
            throw new SummaryException(SummaryException.IN_PROGRESS,
                    "这场面试的对话总结正在生成，请稍候", HttpStatus.CONFLICT);
        }
        try {
            InterviewSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到场次：" + sessionId));
            if (session.getScriptId() == null) {
                throw new SummaryException(SummaryException.NOT_SCRIPTED,
                        "自由对话场次不生成对话总结（仅剧本面试支持）");
            }

            List<InterviewMessage> dialogue = new ArrayList<>();
            int userCount = 0;
            for (InterviewMessage message : messageRepository.findBySessionIdOrderBySeqAsc(sessionId)) {
                if (InterviewMessage.ROLE_SYSTEM.equals(message.getRole())) {
                    continue;
                }
                // 旁白 / 剧情提示借 role=user 落库（见 EngineContext#senderRoleId），它们不是求职者发言：
                // 混进对话会让模型把剧情指令当成回答，还会把「有没有真的答过题」的判断带偏
                if (isPromptLine(message)) {
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
                throw new SummaryException(SummaryException.NO_ENOUGH_DIALOGUE,
                        "本场没有求职者发言，没有可总结的内容");
            }

            String model = configService.get(ConfigKey.LLM_MODEL);
            String raw;
            try {
                raw = complete(session, dialogue);
            } catch (LlmClient.LlmException e) {
                throw new SummaryException(SummaryException.LLM_FAILED,
                        "大模型生成总结失败：" + e.getMessage(), HttpStatus.BAD_GATEWAY, e);
            }

            SummaryParser.SummaryDraft draft = SummaryParser.parse(raw);
            InterviewSummary row = saveOk(sessionId, draft, model);
            log.info("[Summary] 场次 {} 总结完成：{} 轮记录，{} 字（model={}）",
                    sessionId, draft.questions().size(), draft.overview().length(), model);
            return toResult(row, draft);
        } catch (SummaryException e) {
            // 「按规格跳过」的两种（自由对话 / 没有求职者发言）不写 failed：
            // 落一行失败只会在报告页制造噪音，而这两种场次本来就没有可总结的内容
            if (!SummaryException.NOT_SCRIPTED.equals(e.code())
                    && !SummaryException.NO_ENOUGH_DIALOGUE.equals(e.code())) {
                saveFailed(sessionId, e.getMessage());
            }
            throw e;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            SummaryException wrapped = new SummaryException(SummaryException.LLM_FAILED,
                    "生成对话总结失败：" + e.getMessage(), HttpStatus.BAD_GATEWAY, e);
            saveFailed(sessionId, wrapped.getMessage());
            throw wrapped;
        } finally {
            inFlight.remove(sessionId);
        }
    }

    /**
     * 取已有总结；没生成过 → 404。
     *
     * <p>失败记录也返回 200（{@code status=failed} + {@code error}）：报告页要能区分
     * 「还没生成」与「生成失败」，前者不显示该板块，后者显示失败原因与重试按钮。
     */
    public SummaryResult load(Long sessionId) {
        List<InterviewSummary> rows = summaryRepository.findBySessionId(sessionId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "场次 " + sessionId + " 还没有对话总结");
        }
        InterviewSummary row = rows.get(0);
        if (InterviewSummary.STATUS_FAILED.equals(row.getStatus())) {
            return new SummaryResult(row.getSessionId(), row.getStatus(), "", List.of(),
                    new Coverage(List.of(), List.of()), new Vocabulary(List.of(), List.of()),
                    row.getModel(), row.getCreatedAt(), row.getError());
        }
        SummaryParser.SummaryDraft draft = loadContent(row);
        return toResult(row, draft);
    }

    /** 场次被删除时连带清掉总结，避免孤儿行。 */
    public void deleteBySessionId(Long sessionId) {
        List<InterviewSummary> rows = summaryRepository.findBySessionId(sessionId);
        if (!rows.isEmpty()) {
            summaryRepository.deleteAll(rows);
        }
    }

    /**
     * 是否旁白 / 剧情提示行。
     *
     * <p>引擎把这两类借用 {@code role=user} 落库，靠 {@code msg_type} 区分；历史数据里
     * 也可能只留下 {@code {旁白: ...}} 信封。两种判据都认，避免漏网的指令被当成回答。
     */
    private static boolean isPromptLine(InterviewMessage message) {
        if (!InterviewMessage.ROLE_USER.equals(message.getRole())) {
            return false;
        }
        String msgType = message.getMsgType();
        if ("narration".equals(msgType) || "plot".equals(msgType)) {
            return true;
        }
        String content = message.getContent();
        return content != null && content.strip().startsWith("{旁白:");
    }

    /** 短对话一次生成；长对话先分块抽取事实，再合并成完整摘要。 */
    private String complete(InterviewSession session, List<InterviewMessage> dialogue) {
        List<List<InterviewMessage>> chunks = SummaryPrompter.split(dialogue);
        if (chunks.size() <= 1) {
            return llmClient.complete(SummaryPrompter.buildFinal(session.getJobCode(), dialogue));
        }
        List<String> partials = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            partials.add(llmClient.complete(
                    SummaryPrompter.buildChunk(session.getJobCode(), chunks.get(i), i + 1, chunks.size())));
        }
        log.info("[Summary] 场次 {} 对话较长（{} 字），分 {} 段后合并",
                session.getId(), SummaryPrompter.totalChars(dialogue), chunks.size());
        return llmClient.complete(SummaryPrompter.buildMerge(session.getJobCode(), partials));
    }

    /**
     * 成功落库：先删后插覆盖旧记录（唯一约束 {@code uk_summary_session} 保证一场只有一行）。
     *
     * <p>用 {@code deleteAll(实体)} / {@code save} 这类自带事务的仓库方法，而不是
     * {@code @Modifying} 的 JPQL —— 本方法会被失败路径复用，不希望整段包进一个事务。
     */
    private InterviewSummary saveOk(Long sessionId, SummaryParser.SummaryDraft draft, String model) {
        List<InterviewSummary> old = summaryRepository.findBySessionId(sessionId);
        if (!old.isEmpty()) {
            summaryRepository.deleteAll(old);
        }
        InterviewSummary row = new InterviewSummary();
        row.setSessionId(sessionId);
        row.setStatus(InterviewSummary.STATUS_OK);
        row.setContent(toJson(draft));
        row.setModel(model);
        row.setCreatedAt(LocalDateTime.now());
        return summaryRepository.save(row);
    }

    /** 失败落库：报告页据此显示「摘要生成失败」+ 原因。失败原因本身再失败就只记日志。 */
    private void saveFailed(Long sessionId, String message) {
        try {
            List<InterviewSummary> old = summaryRepository.findBySessionId(sessionId);
            if (!old.isEmpty()) {
                summaryRepository.deleteAll(old);
            }
            InterviewSummary row = new InterviewSummary();
            row.setSessionId(sessionId);
            row.setStatus(InterviewSummary.STATUS_FAILED);
            row.setError(message == null ? "未知原因" : message);
            row.setCreatedAt(LocalDateTime.now());
            summaryRepository.save(row);
        } catch (RuntimeException e) {
            log.warn("[Summary] 场次 {} 失败状态落库也失败了：{}", sessionId, e.toString());
        }
    }

    /** 实体 → 返回值；两个路径（刚生成 / 读旧记录）共用。 */
    private SummaryResult toResult(InterviewSummary row, SummaryParser.SummaryDraft draft) {
        return new SummaryResult(row.getSessionId(), InterviewSummary.STATUS_OK, draft.overview(),
                draft.questions(), draft.coverage(), draft.vocabulary(),
                row.getModel(), row.getCreatedAt(), null);
    }

    /**
     * CLOB 里的摘要 JSON → 草稿。
     *
     * <p>历史脏数据（不是 JSON）降级成空摘要，不让「查总结」挂掉：
     * 报告页最多显示一段空白，而不是错误页。
     */
    private SummaryParser.SummaryDraft loadContent(InterviewSummary row) {
        String json = row.getContent();
        if (json == null || json.isBlank()) {
            return new SummaryParser.SummaryDraft("", List.of(),
                    new Coverage(List.of(), List.of()), new Vocabulary(List.of(), List.of()));
        }
        try {
            return SummaryParser.parse(json);
        } catch (SummaryException e) {
            log.warn("[Summary] 场次 {} 的总结内容不可解析，按空摘要处理：{}", row.getSessionId(), json);
            return new SummaryParser.SummaryDraft("", List.of(),
                    new Coverage(List.of(), List.of()), new Vocabulary(List.of(), List.of()));
        }
    }

    /**
     * 草稿 → JSON。直接序列化 record：字段名与 {@link SummaryParser} 的解析口径完全一致，
     * 不需要另写一份映射（写映射最容易在改字段时漏掉一处）。
     */
    private String toJson(SummaryParser.SummaryDraft draft) {
        try {
            return JSON.writeValueAsString(draft);
        } catch (IOException e) {
            // record 里全是字符串列表，序列化不可能真的失败；兜底成空对象而不是抛出去
            log.warn("[Summary] 摘要序列化失败，存空 JSON：{}", e.toString());
            return "{}";
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}

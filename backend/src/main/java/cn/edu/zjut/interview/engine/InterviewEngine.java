package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.UserProfile;
import cn.edu.zjut.interview.dto.SessionDetailView;
import cn.edu.zjut.interview.dto.SessionFinishView;
import cn.edu.zjut.interview.dto.SessionMessageView;
import cn.edu.zjut.interview.dto.SessionSummaryView;
import cn.edu.zjut.interview.engine.events.AiDialogueEvent;
import cn.edu.zjut.interview.engine.events.AmbientEvent;
import cn.edu.zjut.interview.engine.events.BackgroundEffectEvent;
import cn.edu.zjut.interview.engine.events.BackgroundEvent;
import cn.edu.zjut.interview.engine.events.ChapterEndEvent;
import cn.edu.zjut.interview.engine.events.ChoiceEvent;
import cn.edu.zjut.interview.engine.events.DialogEvent;
import cn.edu.zjut.interview.engine.events.FreeDialogueEvent;
import cn.edu.zjut.interview.engine.events.InputEvent;
import cn.edu.zjut.interview.engine.events.ModifyCharacterEvent;
import cn.edu.zjut.interview.engine.events.MusicEvent;
import cn.edu.zjut.interview.engine.events.NarrationEvent;
import cn.edu.zjut.interview.engine.events.PlayerEvent;
import cn.edu.zjut.interview.engine.events.PresentPicEvent;
import cn.edu.zjut.interview.engine.events.SetVariableEvent;
import cn.edu.zjut.interview.engine.events.SoundEvent;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmMessage;
import cn.edu.zjut.interview.prompt.MemoryBuilder;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import cn.edu.zjut.interview.repository.UserProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 剧本引擎，对应 {@code script_engine/script_manager.rs:448 run_script}
 * （外加 {@code chapter.rs}、{@code events_handler.rs} 的推进逻辑）。
 *
 * <p>Rust 是进程内长跑：{@code while next_chapter != "end"} 一路跑，input / free_dialogue
 * 在事件里 {@code rx.await} 挂起线程。Java 没有这个上下文，按规格 §1 映射成
 * 「推进到下一个阻塞点就返回」：进度（章节 + 事件下标 + 变量）每步落
 * {@code interview_session}，下一次请求从 {@code event_sequence} 续跑，刷新不重放。
 */
@Service
public class InterviewEngine {

    private static final Logger log = LoggerFactory.getLogger(InterviewEngine.class);

    /** 剧本结束的哨兵章节名（原版同样用字符串 "end"）。 */
    public static final String CHAPTER_END = "end";

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_FINISHED = "finished";

    /**
     * {@code variable_info} 里的「此刻有事件在等用户输入」标记。
     *
     * <p>只有两处写入：{@link #run} 开头置 {@code false}（一开始推进就不再等）；
     * {@code InputEvent} / {@code FreeDialogueEvent} 执行到位时置 {@code true}。
     * {@code submitAndRun} 靠它判断这次提交该不该被接受（原版 {@code script.rs:244}），
     * 不要在其它的地方写这个键，否则两处会互相覆盖。
     */
    public static final String AWAITING_INPUT_KEY = "awaitingInput";

    /** 事件 YAML 里会被替换占位符的文本字段（events_handler.rs:122 同一张表）。 */
    private static final List<String> PLACEHOLDER_FIELDS = List.of(
            "text", "prompt", "hint", "end_line", "dialog_prompt", "end_prompt", "content", "description");

    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewChapterRepository chapterRepository;
    private final InterviewerRepository interviewerRepository;
    private final InterviewScriptRepository scriptRepository;
    private final UserProfileRepository userProfileRepository;
    private final LlmClient llmClient;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    /** 与 ChatService 共用同一套记忆构建；无状态，直接 new。 */
    private final MemoryBuilder memoryBuilder = new MemoryBuilder();

    public InterviewEngine(InterviewSessionRepository sessionRepository,
                           InterviewMessageRepository messageRepository,
                           InterviewChapterRepository chapterRepository,
                           InterviewerRepository interviewerRepository,
                           InterviewScriptRepository scriptRepository,
                           UserProfileRepository userProfileRepository,
                           LlmClient llmClient,
                           ConfigService configService,
                           ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.chapterRepository = chapterRepository;
        this.interviewerRepository = interviewerRepository;
        this.scriptRepository = scriptRepository;
        this.userProfileRepository = userProfileRepository;
        this.llmClient = llmClient;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /** 生成侧可调项：每次推进读一次后端设置，改完设置下一轮就生效。 */
    private ChatTuning chatTuning() {
        return new ChatTuning(
                configService.getInt(ConfigKey.CHAT_MERGE_THRESHOLD),
                configService.getInt(ConfigKey.CHAT_REPLY_COUNT));
    }

    // ───────────────────────── 主循环（规格 §4 / Rust run_script） ─────────────────────────

    /** 从当前进度推进到下一个阻塞点（input / choices / free_dialogue / 结束）。 */
    public void run(Long sessionId, EventSink sink) {
        InterviewSession session = load(sessionId);
        if (!STATUS_RUNNING.equals(session.getStatus())) {
            // 幂等：已结束的场次再推进只回一个 finished
            sink.finished();
            return;
        }

        ObjectNode vars = readVars(session);
        // 开始推进就不再处于「等输入」态；等下一个 input / free_dialogue 事件执行到位才会重新置 true
        vars.put(AWAITING_INPUT_KEY, false);
        persist(session, vars);
        JsonNode pendingChoice = vars.get(ChoiceEvent.PENDING_KEY);
        if (pendingChoice != null && pendingChoice.isObject()) {
            // 选项挂起中：原版引擎此刻正阻塞在 choice_tx 上，直接推进会越过用户的选择
            log.info("[ScriptEngine] 会话 {} 仍有未决选项，等待用户选择", sessionId);
            return;
        }
        String playerName = playerName(session);
        String userName = userName(session);
        InterviewScript script = script(session);
        String announcedChapter = null;
        // 在场角色集合：本次推进内共享（对应 game_status.present_role_ids），跨请求重置为 {MAIN}
        Set<String> presentRoles = EngineContext.defaultPresentRoles();
        ChatTuning tuning = chatTuning();

        while (true) {
            String code = session.getCurrentChapter();
            if (code == null || code.isBlank()) {
                code = script == null ? null : script.getIntroChapter();
            }
            if (code == null || CHAPTER_END.equals(code)) {
                finish(session, sink);
                return;
            }

            InterviewChapter chapter = script == null ? null
                    : chapterRepository.findByScriptIdAndCode(script.getId(), code).orElse(null);
            if (chapter == null) {
                log.warn("[ScriptEngine] 章节不存在: script={} code={}，剧本结束", session.getScriptId(), code);
                finish(session, sink);
                return;
            }

            if (!code.equals(announcedChapter)) {
                sink.chapter(code, chapter.getName());
                announcedChapter = code;
            }

            JsonNode events = parseEvents(chapter);
            int sequence = session.getEventSequence() == null ? 0 : session.getEventSequence();
            if (sequence > events.size()) {
                // 章节被改短：恢复点失效，截到章末让章节自然结束（script_manager.rs:500 同一处理）
                log.warn("[ScriptEngine] 续跑下标 {} 超出章节事件数 {}，截断到章末", sequence, events.size());
                sequence = events.size();
            }

            String nextChapter = null;
            while (sequence < events.size()) {
                JsonNode rawEvent = events.get(sequence);
                int eventIndex = sequence;
                // 先自增再执行，与 Rust 的 progress += 1 一致：中途失败也不会重放同一个事件
                sequence++;
                session.setCurrentChapter(code);
                session.setEventSequence(sequence);
                persist(session, vars);
                sink.progress(code, eventIndex, (int) messageRepository.countBySessionId(sessionId), vars);

                JsonNode event = resolvePlaceholders(rawEvent, playerName, userName);
                String type = event.path("type").asText("");
                if (type.isEmpty()) {
                    throw new IllegalStateException("事件缺少 'type' 字段，索引: " + eventIndex);
                }
                String condition = event.path("condition").asText("");
                if (!condition.isEmpty() && !ConditionEvaluator.evaluate(condition, vars)) {
                    log.info("[ScriptEngine] 跳过事件 type='{}'（条件不满足），索引: {}", type, eventIndex);
                    continue;
                }

                EngineContext ctx = new EngineContext(session, event, sink, messageRepository,
                        chapterRepository, interviewerRepository, llmClient, objectMapper,
                        playerName, vars, code, presentRoles);
                ctx.tuning(tuning);
                String result;
                try {
                    result = createEvent(type).execute(ctx);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    // 事件失败即整场终止（原版 events_handler 同样把 Err 往上抛）
                    throw new IllegalStateException(e.getMessage() == null ? type + " 事件执行失败" : e.getMessage(), e);
                }
                persist(session, vars);

                if (EngineSignal.isBlocking(result)) {
                    // 阻塞点：本次推进到此为止，用户下次请求从 event_sequence 续跑
                    return;
                }
                if (result != null) {
                    nextChapter = result;
                    break;
                }
            }

            if (nextChapter == null) {
                // 章节事件跑完却没有 chapter_end：原版 get_chapter_result() 缺省返回 "end"
                nextChapter = CHAPTER_END;
            }
            session.setCurrentChapter(nextChapter);
            session.setEventSequence(0);
            persist(session, vars);

            if (CHAPTER_END.equals(nextChapter)) {
                finish(session, sink);
                return;
            }
            log.info("[ScriptEngine] 切换到章节: '{}'", nextChapter);
        }
    }

    /**
     * 对应原版 {@code start_script}：从章节首事件开始跑。
     *
     * <p>和「续跑」走同一个 {@link #run}，差别只在调用方（{@code ScriptPlayController}
     * 的 {@code /script/start}）已经先把 {@code script_id} / {@code event_sequence} /
     * {@code variable_info} 重置成新剧本的起点。
     */
    public void runFromStart(Long sessionId, EventSink sink) {
        run(sessionId, sink);
    }

    /**
     * 提交用户输入后继续推进；返回前先把用户消息落库。
     *
     * <p>三参版本是「自由输入」：选项挂起且 {@code allow_free=false} 时会被拒绝（原版同样的分支）。
     */
    public void submitAndRun(Long sessionId, String text, EventSink sink) {
        submitAndRun(sessionId, text, sink, false);
    }

    /**
     * 提交用户输入后继续推进。
     *
     * @param forceChoice {@code true} 表示这次提交来自「点选项」而不是输入框：此时忽略
     *                    {@code allow_free}，直接按选项文本匹配（原版 {@code script_submit_choice}
     *                    与 {@code script_submit_input} 是两条独立入口，这里用一个开关区分）
     */
    public void submitAndRun(Long sessionId, String text, EventSink sink, boolean forceChoice) {
        InterviewSession session = load(sessionId);
        if (!STATUS_RUNNING.equals(session.getStatus())) {
            sink.finished();
            return;
        }
        ObjectNode vars = readVars(session);
        String input = text == null ? "" : text;

        JsonNode pendingChoice = vars.get(ChoiceEvent.PENDING_KEY);
        boolean hasChoice = pendingChoice != null && pendingChoice.isObject();
        boolean allowFree = hasChoice && pendingChoice.path(ChoiceEvent.ALLOW_FREE_KEY).asBoolean(false);

        // 原版 script.rs:231-236 —— 选项挂起且不接受自由输入 → 拒绝，不消费
        if (hasChoice && !allowFree && !forceChoice) {
            throw new ScriptInputRejectedException("当前的选项不接受自由输入，请点击一个选项");
        }
        if (hasChoice && (allowFree || forceChoice)) {
            resumeChoice(session, vars, sink, input);
            return;
        }

        JsonNode free = vars.get(FreeDialogueEvent.VAR_KEY);
        boolean inFree = free != null && free.isObject() && free.path("switch").asBoolean(false);

        // 原版 script.rs:244 —— 没有等待输入的事件 → 拒绝，不落库
        if (!inFree && !vars.path(AWAITING_INPUT_KEY).asBoolean(false)) {
            throw new ScriptInputRejectedException("当前没有等待输入的脚本事件");
        }

        if (inFree) {
            vars.put(AWAITING_INPUT_KEY, false);
            persist(session, vars);
            freeDialogueTurn(session, vars, sink, input);
            return;
        }

        vars.put(AWAITING_INPUT_KEY, false);
        persist(session, vars);
        // 对应 input_event.rs:70 —— rx.await 回来后先把用户输入落成 USER 台词
        saveUserMessage(session, input);
        run(sessionId, sink);
    }

    // ───────────────────────── 选项挂起与续跑（规格 B7 §任务一.1） ─────────────────────────

    /**
     * 用户选择回来的续跑：匹配选项 → 执行动作 → 清 {@code pendingChoice} → 继续主循环。
     *
     * <p>对应原版 {@code ChoiceEvent::execute} 在 {@code rx.await} 之后那段：
     * <ol>
     *   <li>{@code process_options} 匹配（condition 不满足的项在匹配阶段就被跳过，
     *       所以 disabled 永不命中）</li>
     *   <li>命中 → 执行该 option 的 {@code actions}；全不命中 → 把原始输入落成 USER 台词</li>
     *   <li>清掉挂起状态，继续推进</li>
     * </ol>
     *
     * <p>{@code allowFree == false} 且没命中任何选项时，原版 {@code script_submit_input}
     * 直接返回 Err 且<b>不消费</b> choice 通道（按钮仍可点）。这里等价处理：只记 warn、
     * 不改事件下标、不落用户台词，保持挂起等下一次提交。
     */
    private void resumeChoice(InterviewSession session, ObjectNode vars, EventSink sink, String input) {
        JsonNode pending = vars.get(ChoiceEvent.PENDING_KEY);
        JsonNode options = pending.path(ChoiceEvent.OPTIONS_KEY);
        boolean allowFree = pending.path(ChoiceEvent.ALLOW_FREE_KEY).asBoolean(false);

        JsonNode matched = ChoiceEvent.match(options, input, vars);
        if (matched == null) {
            if (!allowFree) {
                log.warn("[ScriptEngine] 当前选项不接受自由输入，保持挂起：'{}'", input);
                return;
            }
            // choice_event.rs:135-146 —— 没命中时把原始输入作为 USER 台词写入
            saveUserMessage(session, input);
        } else {
            EngineContext ctx = new EngineContext(session, null, sink, messageRepository, chapterRepository,
                    interviewerRepository, llmClient, objectMapper, playerName(session), vars,
                    session.getCurrentChapter());
            ChoiceEvent.handleActions(ctx, matched.get("actions"));
        }

        vars.remove(ChoiceEvent.PENDING_KEY);
        persist(session, vars);
        log.info("[ScriptEngine] 选项已解决（{}），继续主循环", matched == null ? "自由输入" : "命中选项");
        run(session.getId(), sink);
    }

    // ───────────────────────── 自由对话（规格 §5） ─────────────────────────

    /**
     * 自由对话的一轮：落用户台词 → 判定是不是最后一轮 → 生成回复 → 收尾则继续主循环。
     *
     * <p>原版是进程内循环 + {@code rx.await}；这里每轮一个请求，状态在
     * {@code variable_info.freeDialogue} 里（{@code switch / maxRounds / endLine / used}）。
     *
     * <p>收尾判定照 {@code free_dialogue_event.rs:150-201}，两个条件任一成立即最后一轮：
     * <ol>
     *   <li>{@code max_rounds > 0 && rounds >= max_rounds}（{@code :155}）——剧本没写
     *       {@code max_rounds} 时存 {@code -1}（{@code :69}），这条永不成立</li>
     *   <li>{@code !end_line.is_empty() && user_input.contains(end_line)}（{@code :199}）——
     *       子串匹配，所以「没写 max_rounds」的自由对话靠玩家打出结束词收尾</li>
     * </ol>
     * 判定必须在生成回复<b>之前</b>完成：原版据此选 {@code end_prompt} / {@code dialog_prompt}
     * （{@code :204-208}），收尾轮的剧情提示不一样。
     */
    private void freeDialogueTurn(InterviewSession session, ObjectNode vars, EventSink sink, String text) {
        String playerName = playerName(session);
        saveUserMessage(session, text);

        ObjectNode free = (ObjectNode) vars.get(FreeDialogueEvent.VAR_KEY);
        int used = free.path("used").asInt(0) + 1;
        int maxRounds = free.path("maxRounds").asInt(-1);
        String endLine = free.path("endLine").asText("");
        boolean lastRound = (maxRounds > 0 && used >= maxRounds)
                || (!endLine.isEmpty() && text.contains(endLine));
        free.put("used", used);

        EngineContext ctx = new EngineContext(session, null, sink, messageRepository, chapterRepository,
                interviewerRepository, llmClient, objectMapper, playerName, vars,
                session.getCurrentChapter());
        ctx.tuning(chatTuning());
        List<InterviewMessage> history = messageRepository.findBySessionIdOrderBySeqAsc(session.getId());
        List<LlmMessage> messages = memoryBuilder.build(history);
        DialogueWriter.writeTurn(ctx, messages, lastUserSeq(history));

        if (!lastRound) {
            persist(session, vars);
            log.info("[ScriptEngine] 自由对话第 {} 轮（{}），继续等待输入", used,
                    maxRounds > 0 ? maxRounds + " 轮制" : "无限轮");
            return;
        }

        if (!endLine.isEmpty()) {
            ctx.saveMessage(InterviewMessage.ROLE_USER, PromptEnvelope.narrator(endLine), "narration",
                    null, null, null);
            sink.narration(endLine);
        }
        vars.remove(FreeDialogueEvent.VAR_KEY);
        persist(session, vars);
        log.info("[ScriptEngine] 自由对话结束（{} 轮），继续主循环", used);
        run(session.getId(), sink);
    }

    // ───────────────────────── 场次查询（规格 §6.1） ─────────────────────────

    /** 只校验场次存在（Controller 在建 SSE 之前同步抛 404，避免把 404 变成流里的 error）。 */
    public void ensureExists(Long sessionId) {
        load(sessionId);
    }

    public SessionDetailView detail(Long sessionId) {        InterviewSession session = load(sessionId);
        InterviewScript script = script(session);
        var interviewer = interviewerRepository.findById(session.getInterviewerId()).orElse(null);
        List<SessionMessageView> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId)
                .stream().map(SessionMessageView::of).toList();
        return new SessionDetailView(
                session.getId(),
                session.getTitle(),
                session.getStatus(),
                session.getScore(),
                session.getJobCode(),
                session.getKps(),
                session.getCurrentChapter(),
                session.getEventSequence(),
                script == null ? null : script.getCode(),
                interviewer == null ? null : interviewer.getCode(),
                messages);
    }

    public SessionFinishView finishNow(Long sessionId) {
        InterviewSession session = load(sessionId);
        session.setStatus(STATUS_FINISHED);
        session.setEndedAt(LocalDateTime.now());
        // 批次 4.2：不再用写死公式算分，score 留空（结束报告分数由大模型评估管线后续接管）
        sessionRepository.save(session);
        return new SessionFinishView(session.getId(), session.getStatus(), session.getScore());
    }

    public List<SessionSummaryView> list(String status) {
        // 规格 E5b §1.1：试玩场次不进面试记录（对齐原版试玩不写 save / 不解锁成就）
        List<InterviewSession> sessions = status == null || status.isBlank()
                ? sessionRepository.findAllByPreviewFalseOrderByCreatedAtDesc()
                : sessionRepository.findByStatusAndPreviewFalseOrderByCreatedAtDesc(status);
        return sessions.stream().map(this::summary).toList();
    }

    private SessionSummaryView summary(InterviewSession session) {
        InterviewScript script = script(session);
        var interviewer = interviewerRepository.findById(session.getInterviewerId()).orElse(null);
        return new SessionSummaryView(
                session.getId(),
                session.getTitle(),
                session.getStatus(),
                session.getScore(),
                session.getJobCode(),
                script == null ? null : script.getCode(),
                interviewer == null ? null : interviewer.getCode(),
                session.getStartedAt(),
                session.getEndedAt());
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private ScriptEvent createEvent(String type) {
        return switch (type) {
            case NarrationEvent.TYPE -> new NarrationEvent();
            case AiDialogueEvent.TYPE -> new AiDialogueEvent();
            case InputEvent.TYPE -> new InputEvent();
            case ModifyCharacterEvent.TYPE -> new ModifyCharacterEvent();
            case FreeDialogueEvent.TYPE -> new FreeDialogueEvent();
            case ChapterEndEvent.TYPE -> new ChapterEndEvent();
            case ChoiceEvent.TYPE -> new ChoiceEvent();
            case DialogEvent.TYPE -> new DialogEvent();
            case SetVariableEvent.TYPE -> new SetVariableEvent();
            case PlayerEvent.TYPE -> new PlayerEvent();
            case PresentPicEvent.TYPE -> new PresentPicEvent();
            case BackgroundEvent.TYPE -> new BackgroundEvent();
            case BackgroundEffectEvent.TYPE -> new BackgroundEffectEvent();
            case MusicEvent.TYPE -> new MusicEvent();
            case SoundEvent.TYPE -> new SoundEvent();
            case AmbientEvent.TYPE -> new AmbientEvent();
            default -> new NoopEvent(type);
        };
    }

    private InterviewSession load(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + sessionId));
    }

    private InterviewScript script(InterviewSession session) {
        return session.getScriptId() == null
                ? null
                : scriptRepository.findById(session.getScriptId()).orElse(null);
    }

    private JsonNode parseEvents(InterviewChapter chapter) {
        try {
            JsonNode events = objectMapper.readTree(chapter.getEvents() == null ? "[]" : chapter.getEvents());
            return events.isArray() ? events : objectMapper.createArrayNode();
        } catch (Exception e) {
            throw new IllegalStateException("章节事件 JSON 解析失败：" + chapter.getCode(), e);
        }
    }

    /**
     * 事件执行前替换文本字段里的占位符（events_handler.rs:120 resolve_placeholders）。
     *
     * <p>替换 {@code %player%} 与导入器归一后的 {@code {user}}；返回副本，不改库里原 JSON。
     */
    private JsonNode resolvePlaceholders(JsonNode event, String playerName, String userName) {
        if (event == null || !event.isObject()) {
            return event;
        }
        ObjectNode copy = event.deepCopy();
        for (String field : PLACEHOLDER_FIELDS) {
            JsonNode node = copy.get(field);
            if (node != null && node.isTextual()) {
                copy.put(field, ScriptFunctions.replacePlaceholders(node.asText(), playerName, userName));
            }
        }
        return copy;
    }

    /** 变量表：{@code variable_info} 缺省 {@code {}}；非对象也当空表处理。 */
    private ObjectNode readVars(InterviewSession session) {
        String raw = session.getVariableInfo();
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(raw);
                if (node.isObject()) {
                    return (ObjectNode) node;
                }
            } catch (Exception e) {
                log.warn("[ScriptEngine] variable_info 解析失败，按空变量表处理：{}", e.toString());
            }
        }
        return objectMapper.createObjectNode();
    }

    private void persist(InterviewSession session, ObjectNode vars) {
        session.setVariableInfo(vars.toString());
        sessionRepository.save(session);
    }

    private void finish(InterviewSession session, EventSink sink) {
        session.setStatus(STATUS_FINISHED);
        if (session.getEndedAt() == null) {
            session.setEndedAt(LocalDateTime.now());
        }
        // 批次 4.2：不再算分，score 留空
        sessionRepository.save(session);
        sendFinished(sink);
        log.info("[ScriptEngine] 会话 {} 剧本结束", session.getId());
    }

    /** {@code sink.finished()} 异常不应影响落库结果。 */
    private void sendFinished(EventSink sink) {
        try {
            sink.finished();
        } catch (RuntimeException e) {
            log.debug("[ScriptEngine] finished 推送失败（连接可能已断开）：{}", e.toString());
        }
    }

    private void saveUserMessage(InterviewSession session, String text) {
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(session.getId());
        message.setSeq(messageRepository.findMaxSeqBySessionId(session.getId()) + 1);
        message.setRole(InterviewMessage.ROLE_USER);
        message.setContent(text == null ? "" : text);
        message.setMsgType("text");
        message.setSenderRoleId(InterviewMessage.PLAYER_ROLE_ID);
        message.setStageCode(session.getCurrentChapter());
        message.setCreatedAt(LocalDateTime.now());
        messageRepository.save(message);
    }

    private Integer lastUserSeq(List<InterviewMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (InterviewMessage.ROLE_USER.equals(history.get(i).getRole())) {
                return history.get(i).getSeq();
            }
        }
        return null;
    }

    /** {@code user_profile.nickname}；profile 缺失或昵称为空时 {@code null}。 */
    private String nickname() {
        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc().orElse(null);
        if (profile == null || profile.getNickname() == null || profile.getNickname().isBlank()) {
            return null;
        }
        return profile.getNickname();
    }

    /**
     * {@code %player%} 的取值：用户昵称（原版取 {@code game_status.player.user_name}），
     * 本项目落在 {@code user_profile.nickname}；profile 缺失时退到「候选人」。
     */
    private String playerName(InterviewSession session) {
        String nickname = nickname();
        return nickname == null ? "候选人" : nickname;
    }

    /**
     * {@code {user}} 的取值：同样是 {@code user_profile.nickname}，但剧本里它出现在旁白与剧情提示中
     * （「于是钦灵和{user}整理好了小包」），profile 缺失时按玩家视角叙述成「你」，
     * 而不是把「候选人」这种后台兜底词念进台词。
     */
    private String userName(InterviewSession session) {
        String nickname = nickname();
        return nickname == null ? ScriptFunctions.USER_FALLBACK : nickname;
    }
}

package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.domain.PlayerIdentity;
import cn.edu.zjut.interview.domain.UserProfile;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.dto.SessionMessageView;
import cn.edu.zjut.interview.dto.KnowledgeHit;
import cn.edu.zjut.interview.god.GodAgentCore;
import cn.edu.zjut.interview.god.SpeakerSelector;
import cn.edu.zjut.interview.knowledge.KnowledgeInjector;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmMessage;
import cn.edu.zjut.interview.llm.LlmOptions;
import cn.edu.zjut.interview.llm.ReplyLengthMode;
import cn.edu.zjut.interview.prompt.InterviewPlan;
import cn.edu.zjut.interview.prompt.MemoryBuilder;
import cn.edu.zjut.interview.prompt.PlayerIdentityPrompt;
import cn.edu.zjut.interview.prompt.PromptAssembler;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import cn.edu.zjut.interview.repository.UserProfileRepository;
import cn.edu.zjut.interview.stream.EmotionDictionary;
import cn.edu.zjut.interview.stream.EmotionSegment;
import cn.edu.zjut.interview.stream.SentenceSplitter;
import cn.edu.zjut.interview.stream.SymbolParser;
import cn.edu.zjut.interview.worldbook.Worldbook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对话链路服务：建会话 + 流式对话（规格 B3-BE §一 / §二）。
 *
 * <p>只做自由对话主干：拼 prompt → 调 DeepSeek → 分句 → 解析 → 落库 → SSE 推回。
 * 设置项（音量/语速/TTS）、章节引擎、评分都不在这里。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** SSE 连接的兜底超时，比 LLM 的 60s 长一截。 */
    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    /** 规格 §1.2：展示时长 = max(1.2, 字数 * 0.08)。 */
    private static final double DURATION_BASE = 1.2;
    private static final double DURATION_PER_CHAR = 0.08;

    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewerRepository interviewerRepository;
    private final UserProfileRepository userProfileRepository;
    private final InterviewScriptRepository scriptRepository;
    private final InterviewChapterRepository chapterRepository;
    private final LlmClient llmClient;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    /** 「我的身份」：玩家台词落库要写下是哪个身份说的、叫什么名字。 */
    private final PlayerIdentityService playerIdentities;

    /** 身份/关系块渲染（玩家块 + 其他角色块）。 */
    private final PlayerIdentityPrompt identityPrompt;

    /** 角色对各方的出厂关系（说话者侧 + 场上其他角色侧，对齐 relations.yml）。 */
    private final InterviewerRelationService interviewerRelations;

    /** 上帝 Agent：多人对话的选人判定与轮数上限（单人场景不激活，零开销）。 */
    private final GodAgentCore godAgent;

    /**
     * 岗位知识注入（K4）：按玩家本轮提问检索知识库，命中则拼进当轮 system。
     *
     * <p>开关关闭、没配语料、没命中、检索异常，四种情况都返回空注入 ——
     * 这条链路只做加法，任何一种退化都不该影响原有对话。
     */
    private final KnowledgeInjector knowledgeInjector;

    /**
     * 档位变更时的一次性 announce 文案（批次 4.4，对齐 take_temporary_memories 的 take 语义）：
     * PUT 成功时 put，runTurn 取走即弃、不落库。重启丢失可接受（原版也是会话内存态）。
     */
    private final Map<Long, String> pendingAnnounce = new ConcurrentHashMap<>();

    /** 上一轮台词条数越界时的纠偏文案，注入下一轮后取走即弃。 */
    private final Map<Long, String> pendingCorrection = new ConcurrentHashMap<>();

    /** 已验收的拼装/分句组件，直接复用，不复制逻辑。 */
    private final MemoryBuilder memoryBuilder = new MemoryBuilder();
    private final SymbolParser symbolParser = new SymbolParser();

    /**
     * 会话级生成锁（对齐原版 {@code AppState.generation_lock}）。
     *
     * <p>回溯（{@link #rollback}）必须先拿到它才动手：否则正在跑的那一轮会在截断之后
     * 继续往库里写，界面上就冒出一句「不该存在的回复」。原版 {@code rollback_conversation}
     * 开头就是 {@code gen_lock.lock().await}，语义完全一样。
     */
    private final Map<Long, ReentrantLock> generationLocks = new ConcurrentHashMap<>();

    private ReentrantLock generationLock(Long sessionId) {
        return generationLocks.computeIfAbsent(sessionId, id -> new ReentrantLock());
    }

    /** SSE 输出是阻塞写，每个请求占一个线程；4 个并发的个人项目够用。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chat-sse-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    public ChatService(InterviewSessionRepository sessionRepository,
                       InterviewMessageRepository messageRepository,
                       InterviewerRepository interviewerRepository,
                       UserProfileRepository userProfileRepository,
                       InterviewScriptRepository scriptRepository,
                       InterviewChapterRepository chapterRepository,
                       LlmClient llmClient,
                       ConfigService configService,
                       ObjectMapper objectMapper,
                       PlayerIdentityService playerIdentities,
                       InterviewerRelationService interviewerRelations,
                       GodAgentCore godAgent,
                       KnowledgeInjector knowledgeInjector) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.interviewerRepository = interviewerRepository;
        this.userProfileRepository = userProfileRepository;
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.llmClient = llmClient;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.playerIdentities = playerIdentities;
        this.identityPrompt = new PlayerIdentityPrompt(objectMapper);
        this.interviewerRelations = interviewerRelations;
        this.godAgent = godAgent;
        this.knowledgeInjector = knowledgeInjector;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ───────────────────────── 建会话（规格 §1.1，5 步顺序不变） ─────────────────────────

    /**
     * 建场次 + 落 system 串 + 落开场白。
     *
     * <p>system 串<b>只在开场拼一次</b>，之后每轮从库里读，不再重拼——这条是 1:1 复刻的核心。
     */
    public CreateSessionResponse createSession(CreateSessionRequest request) {
        String interviewerCode = request == null || request.interviewerCode() == null
                ? "strict" : request.interviewerCode();
        String jobId = request == null || request.jobId() == null || request.jobId().isBlank()
                ? "java" : request.jobId();
        String scriptCode = request == null || request.scriptCode() == null || request.scriptCode().isBlank()
                ? null : request.scriptCode();

        // 1. 查 interviewer + user_profile
        Interviewer interviewer = interviewerRepository.findByCode(interviewerCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "面试官不存在：" + interviewerCode));
        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc().orElse(null);
        // 不指定剧本就是纯自由对话（原版 send_chat_message 那条链路）：不按岗位/面试官去猜一个绑上，
        // 剧本必须由 /script/start 显式开场
        InterviewScript script = scriptCode == null
                ? null
                : scriptRepository.findByCode(scriptCode)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "剧本不存在：" + scriptCode));

        // 2. 拼完整 system 串
        String systemPrompt = buildSystemPrompt(interviewer, profile, script);

        // 3. 建 interview_session 行
        InterviewSession session = new InterviewSession();
        session.setTitle(interviewer.getName() + " · " + jobId);
        session.setUserId(profile == null ? 1L : profile.getId());
        session.setInterviewerId(interviewer.getId());
        session.setScriptId(script == null ? null : script.getId());
        session.setJobCode(jobId);
        session.setStatus("running");
        // 在场角色：默认只有主面试官（多人由 setPresentRoleIds 追加）
        session.setPresentRoleIds(String.valueOf(interviewer.getId()));
        session.setEventSequence(0);
        session.setStartedAt(LocalDateTime.now());
        session.setCreatedAt(LocalDateTime.now());
        session = sessionRepository.save(session);

        // 4. 落 system 消息（seq=0，整串原样存）
        saveMessage(session.getId(), 0, InterviewMessage.ROLE_SYSTEM, systemPrompt, null, null, null, null);

        // 5. 落开场白（seq=1，情绪从 opening 里解析，没有就「正常」）
        //    自由对话（script == null）不再把开场白落库：开场白只是给前端看的欢迎语，
        //    不进 LLM 记忆，之后 seq 自然从 max+1 起。剧本会话保留原行为。
        String opening = interviewer.getOpening() == null ? "" : interviewer.getOpening();
        List<EmotionSegment> segments = symbolParser.parse(opening);
        EmotionSegment first = segments.isEmpty() ? null : segments.get(0);
        String openingMessage = first == null ? opening.strip() : first.followingText();
        String openingEmotion = first == null
                ? EmotionDictionary.getInstance().predict(null) : first.predicted();
        if (script != null) {
            saveMessage(session.getId(), 1, InterviewMessage.ROLE_ASSISTANT, opening,
                    first == null ? null : first.originalTag(), openingEmotion,
                    first == null ? null : first.motionText(), interviewerRoleId(interviewer),
                    null, interviewer.getName());
        }

        log.info("[ChatService] 建会话 id={} interviewer={} job={} systemPromptLength={}",
                session.getId(), interviewerCode, jobId, systemPrompt.length());

        return new CreateSessionResponse(
                session.getId(),
                new CreateSessionResponse.InterviewerView(interviewer.getCode(), interviewer.getName(),
                        interviewer.getDisplayRole(), EmotionDictionary.getInstance().predict(null)),
                new CreateSessionResponse.OpeningView(openingMessage, openingEmotion, 1),
                systemPrompt.length());
    }

    // ───────────────────────── 查消息（规格 §1.3） ─────────────────────────

    public List<SessionMessageView> listMessages(Long sessionId, int afterSeq) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + sessionId);
        }
        return messageRepository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq)
                .stream()
                .map(SessionMessageView::of)
                .toList();
    }

    // ───────────────────────── 发消息（规格 §二，10 步） ─────────────────────────

    /**
     * 流式对话：先在调用线程里校验场次（不存在直接 404），再把流式输出丢到线程池，
     * 返回一个已经接上的 {@link SseEmitter}。
     */
    public SseEmitter processMessage(Long sessionId, String text) {
        // 1. 查 session（不存在 → 404）
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + sessionId));
        Interviewer interviewer = interviewerRepository.findById(session.getInterviewerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "面试官不存在：" + session.getInterviewerId()));

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        executor.submit(() -> runTurn(session, interviewer, text == null ? "" : text, emitter));
        return emitter;
    }

    /**
     * 一轮对话（对齐 {@code generator/mod.rs:140-201} 的生成循环）。
     *
     * <p>原来是「存 user → 取历史 → 流式 → 完」一条直线；多人编排后改成循环：
     * 每轮生成完交给上帝 Agent 决定「下一个说话的是谁」，选到 NPC 就再跑一轮，
     * 选到玩家（或轮数到顶 / 上帝 Agent 未激活）就收尾。
     *
     * <p><b>单人面试走的是完全相同的代码路径</b>：{@code present_role_ids} 里只有一个
     * 面试官，{@code shouldActivate} 为假，循环第一次就 break —— 请求条数与落库内容与
     * 改造前逐字一致（验收见 {@code ChatGodAgentLoopTest}）。
     */
    private void runTurn(InterviewSession session, Interviewer interviewer, String text, SseEmitter emitter) {
        // 整轮生成持锁（对齐原版 run_chat 对 generation_lock 的持有范围）：
        // 回溯要等这一轮写完才能截断，否则会冒出「不该存在的回复」。
        ReentrantLock lock = generationLock(session.getId());
        lock.lock();
        try {
            // 2. 首条 system 缺失 → 现场补一条（防老数据）
            ensureSystemMessage(session, interviewer);

            // 3. 保存 user 消息：seq = max+1，content = appendUserMessage(text).main
            String main = symbolParser.appendUserMessage(text).main();
            int userSeq = messageRepository.findMaxSeqBySessionId(session.getId()) + 1;
            saveMessage(session.getId(), userSeq, InterviewMessage.ROLE_USER, main, null, null, null,
                    InterviewMessage.PLAYER_ROLE_ID);

            // 4. emit ai:thinking
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("isThinking", true);
            thinking.put("duration", 0);
            send(emitter, "ai:thinking", thinking);

            LlmOptions options = LlmOptions.from(configService);
            StringBuilder accumulated = new StringBuilder();
            Interviewer speaker = interviewer;
            int consecutiveNpcRounds = 0;
            int rounds = 0;

            while (true) {
                // 5. 读全部消息 → MemoryBuilder（每轮重读：上一轮刚落的台词要进下一轮的上下文）
                List<InterviewMessage> history = messageRepository.findBySessionIdOrderBySeqAsc(session.getId());
                // 第 2 轮起换人说话，system 串必须换成那位面试官的（第 1 轮沿用库里那条，
                // 保证单人场景逐字不变）
                List<LlmMessage> messages = memoryBuilder.build(history);
                if (rounds > 0 && !messages.isEmpty()
                        && LlmMessage.ROLE_SYSTEM.equals(messages.get(0).role())) {
                    messages.set(0, LlmMessage.system(buildSystemPrompt(speaker,
                            userProfileRepository.findFirstByOrderByIdAsc().orElse(null),
                            session.getScriptId() == null ? null
                                    : scriptRepository.findById(session.getScriptId()).orElse(null))));
                }

                // 5.5 批次 4.3 世界书注入：每轮从当前 speaker 的 interviewer 重取、解析结果
                // 每轮重算不缓存；第 1 轮 userInput = 玩家输入，第 2 轮起传 null
                // （对齐原版 pre.rs:113-122 的语义）。
                // 铁律：只改本轮请求副本，绝不写回台词表——messages 每轮重建，天然满足；
                // 注入内容绝不 saveMessage。库列为空时 parse 返回 null，零开销跳过。
                Worldbook worldbook = Worldbook.parse(speaker.getWorldbook());
                if (worldbook != null) {
                    worldbook.inject(messages, rounds == 0 ? main : null);
                }

                // 5.6 批次 4.4 一次性临时 system 注入（announce / 上一轮纠偏），取走即弃、不落库
                injectPendingSystemMessages(messages, session.getId());

                // 5.7 K4 岗位知识注入：每轮都用玩家本轮的原始输入检索（不学世界书传 rounds==0 ? main : null
                // ——那条是「按轮次激活」的语义，知识注入没有；第二轮 NPC 抢话问的还是玩家那句）。
                // 与 5.5 同一铁律：只改本轮请求副本，绝不写回台词表。
                injectKnowledge(messages, main, speaker, session, emitter);

                // 6~8. LlmClient.stream → SentenceSplitter → SymbolParser → 落库 + emit
                // 思考链缓冲：对齐 producer.rs:181-193 —— 累积本轮全部 reasoning_content，
                // 部分供应商会在流结束时重发完整快照，新片以已有内容为前缀时整体替换而非追加。
                StringBuilder thinkingBuf = new StringBuilder();
                ReplyPublisher publisher = new ReplyPublisher(emitter, session, speaker, userSeq);
                SentenceSplitter splitter = new SentenceSplitter(
                        configService.getInt(ConfigKey.CHAT_MERGE_THRESHOLD),
                        configService.getInt(ConfigKey.CHAT_REPLY_COUNT));
                final StringBuilder thinkingRef = thinkingBuf;
                LlmOptions optionsWithReasoning = options.withReasoning(reasoning -> {
                    // 同步替换/追加，语义同 producer.rs：快照前缀则整体替换
                    String current = thinkingRef.toString();
                    if (reasoning.startsWith(current)) {
                        thinkingRef.setLength(0);
                        thinkingRef.append(reasoning);
                    } else {
                        thinkingRef.append(reasoning);
                    }
                    // 对齐 emit_thinking_progress：只推字数，不推正文
                    send(emitter, "ai:thinking",
                            Map.of("isThinking", true, "duration", 0,
                                    "thinkingLength", thinkingRef.codePointCount(0, thinkingRef.length())));
                });
                llmClient.stream(messages, optionsWithReasoning, chunk -> {
                    for (SentenceSplitter.Sentence sentence : splitter.feed(chunk)) {
                        accumulated.append(sentence.text());
                        publisher.offer(sentence);
                    }
                });
                publisher.setThinking(thinkingBuf.toString());
                // 收尾句：SentenceSplitter 的 finish() 给出最后一段
                for (SentenceSplitter.Sentence sentence : splitter.finish()) {
                    accumulated.append(sentence.text());
                    publisher.offer(sentence);
                }
                // 呈现栅栏：本轮所有 ai:reply 落完才允许收尾（本项目聊天链路暂无工具事件，
                // publisher 是唯一的 emit 出口，顺序由此保证）
                publisher.finish();

                // 批次 4.4 纠偏：统计本轮实际落地台词条数，越界则把纠偏文案注入下一轮
                // （取走即弃、不落库）；free 档 acceptedRange 为 null，correctionText 恒 null
                ReplyLengthMode mode = ReplyLengthMode.fromStrLossy(session.getReplyLengthMode());
                String correction = mode.correctionText(publisher.published());
                if (correction != null) {
                    pendingCorrection.put(session.getId(), correction);
                }

                rounds++;
                consecutiveNpcRounds++;
                int nextRoleId = postSelect(session, speaker, consecutiveNpcRounds, history, options);
                if (nextRoleId == 0) {
                    break;
                }
                Interviewer next = interviewerRepository.findById((long) nextRoleId).orElse(null);
                if (next == null) {
                    log.warn("[ChatService] 上帝 Agent 选中的面试官 {} 不存在，本轮收尾", nextRoleId);
                    break;
                }
                speaker = next;
            }

            // 9. 流结束：空回复兜底 + status:reset
            // （对齐 generator/mod.rs:505-524：无正文要显式报错，否则前端一直停在「思考中」）
            if (accumulated.toString().isBlank()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("errorCode", "LLM_EMPTY");
                empty.put("detail", "模型没有返回任何内容，请再试一次");
                send(emitter, "ai:error", empty);
            }
            send(emitter, "status:reset", Map.of());
            emitter.complete();
            log.info("[ChatService] 会话 {} 本轮结束：userSeq={} 轮数={}",
                    session.getId(), userSeq, rounds);
        } catch (Exception e) {
            // 10. 任何异常：ai:error + status:reset
            String errorCode = e instanceof LlmClient.LlmException llm && llm.isTimeout()
                    ? "LLM_TIMEOUT" : "LLM_ERROR";
            log.warn("[ChatService] 会话 {} 本轮失败：{}", session.getId(), e.toString());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("errorCode", errorCode);
            error.put("detail", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            send(emitter, "ai:error", error);
            send(emitter, "status:reset", Map.of());
            emitter.complete();
        } finally {
            lock.unlock();
        }
    }

    // ───────────────────────── 批次 4.4：档位 HTTP 支持 + 调试指令 ─────────────────────────

    /** 当前档位（{@code GET /api/chat/sessions/{id}/reply-length-mode}）。 */
    public String getReplyLengthMode(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + sessionId));
        // 试玩会话整个状态都会在结束时还原，此时读取的档位没有意义（对齐 pending_preview_restore）
        if (Boolean.TRUE.equals(session.isPreview())) {
            return null;
        }
        return ReplyLengthMode.fromStrLossy(session.getReplyLengthMode()).asStr();
    }

    /**
     * 修改档位（{@code PUT /api/chat/sessions/{id}/reply-length-mode}）：
     * 非法值 400、试玩会话 409；成功后把 announce 文案挂进 take 语义的临时表，
     * 下一轮 runTurn 取走即弃（不落库、不写历史）。
     */
    public String setReplyLengthMode(Long sessionId, String rawMode) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + sessionId));
        if (Boolean.TRUE.equals(session.isPreview())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "试玩会话的档位会在结束时整体还原，不能修改");
        }
        ReplyLengthMode mode;
        try {
            mode = ReplyLengthMode.parseStrict(rawMode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        session.setReplyLengthMode(mode.asStr());
        sessionRepository.save(session);
        pendingAnnounce.put(sessionId, mode.announceText());
        return mode.asStr();
    }

    /**
     * 把 pending 的 announce / 纠偏文案作为一次性临时 system 注入本轮 messages。
     * 插在首条 system 之后（没有 system 就插在最前），不落库；取走即弃。
     */
    private void injectPendingSystemMessages(List<LlmMessage> messages, Long sessionId) {
        String announce = pendingAnnounce.remove(sessionId);
        String correction = pendingCorrection.remove(sessionId);
        if (announce == null && correction == null) {
            return;
        }
        int at = 0;
        if (!messages.isEmpty() && LlmMessage.ROLE_SYSTEM.equals(messages.get(0).role())) {
            at = 1;
        }
        if (announce != null) {
            messages.add(at, LlmMessage.system(announce));
        }
        if (correction != null) {
            messages.add(at, LlmMessage.system(correction));
        }
    }

    /**
     * K4：把命中片段拼到 system 末尾，并推一条 {@code ai:knowledge} 给前端。
     *
     * <p>为什么追加到 system 而不是另起一条 system：世界书已经追加在 system 里，
     * 两条相邻的 system 对模型等价，但 history 重建时把「本轮 system + 资料」当成一份更简单。
     *
     * <p>事件 payload 只给「引用了哪些片段」的元信息（code/标题/小节/分数），
     * <b>不带正文</b>：正文已经在 prompt 里了，没必要再往前端推一遍几百字。
     */
    private void injectKnowledge(List<LlmMessage> messages, String userInput, Interviewer speaker,
                                 InterviewSession session, SseEmitter emitter) {
        KnowledgeInjector.Injection injection = knowledgeInjector.build(
                userInput, speaker.getCode(), session.getJobCode());
        if (injection.isEmpty()) {
            return;
        }
        String block = injection.block();
        if (messages.isEmpty() || !LlmMessage.ROLE_SYSTEM.equals(messages.get(0).role())) {
            messages.add(0, LlmMessage.system(block));
        } else {
            messages.set(0, LlmMessage.system(messages.get(0).content() + "\n\n" + block));
        }

        List<Map<String, Object>> items = new ArrayList<>(injection.hits().size());
        for (KnowledgeHit hit : injection.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docCode", hit.docCode());
            item.put("docTitle", hit.docTitle());
            item.put("heading", hit.heading());
            item.put("score", Math.round(hit.score() * 10000.0) / 10000.0);
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put("items", items);
        send(emitter, "ai:knowledge", payload);
    }

    /**
     * 调试指令（对齐 {@code api/chat.rs:301-347}）：仅在后端日志输出，不发往前端。
     *
     * <p>实现差异说明：原版桌面端没有「前端等流式事件」的问题，本项目前端在等
     * SSE 流，处理完不发 {@code status:reset} 前端会永久停在「思考中」，故打完后补发。
     * 指令本身不落库（对齐原版调试指令不进台词列表）。
     */
    private void handleDebugCommand(InterviewSession session, Interviewer interviewer,
                                    String text, SseEmitter emitter) {
        try {
            if ("/查看记忆".equals(text)) {
                List<InterviewMessage> history =
                        messageRepository.findBySessionIdOrderBySeqAsc(session.getId());
                log.info("=== 角色记忆 [{}] (session_id={}) ===",
                        interviewer.getName(), session.getId());
                for (LlmMessage message : memoryBuilder.build(history)) {
                    log.info("[{}] {}", message.role(), message.content());
                }
            } else {
                // 对齐 chat.rs:478 的未知指令文案
                log.warn("未知调试指令: {}。可用指令: /查看记忆", text);
            }
        } finally {
            send(emitter, "status:reset", Map.of());
            emitter.complete();
        }
    }

    /**
     * 回溯对话：删掉第 {@code messageSeq} 条用户消息<b>及其之后</b>的全部台词。
     *
     * <p>对齐 {@code api/chat.rs:502 rollback_conversation}：
     * <ol>
     *   <li>先拿 {@code generation_lock}（原版 {@code gen_lock.lock().await}）——
     *       等正在跑的那一轮写完，否则截断后还会被补写回来；</li>
     *   <li>按 1-based 序号数到第 N 条 {@code role=user} 的行，记下它的 {@code seq}；</li>
     *   <li>删 {@code seq >= 该 seq} 的所有行（原版 {@code truncate(idx)} 含锚点本身，
     *       玩家才能把这句话重说一遍）；</li>
     *   <li>返回截断后的剩余台词（原版返回重建的 {@code GameLineInit}，这里直接给视图）。</li>
     * </ol>
     *
     * @return 截断后剩下的台词（含重新编号的 {@code userMessageSeq} 由控制器补）
     */
    @Transactional
    public List<SessionMessageView> rollback(Long sessionId, int messageSeq) {
        if (messageSeq < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回溯序号必须 >= 1，收到 " + messageSeq);
        }
        if (!sessionRepository.existsById(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + sessionId);
        }
        ReentrantLock lock = generationLock(sessionId);
        boolean acquired;
        try {
            acquired = lock.tryLock(GENERATION_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "等待生成结束被中断");
        }
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "上一轮还在生成中，请稍后重试");
        }
        try {
            List<InterviewMessage> all = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
            int count = 0;
            Integer cutSeq = null;
            for (InterviewMessage message : all) {
                if (InterviewMessage.ROLE_USER.equals(message.getRole())) {
                    count += 1;
                    if (count == messageSeq) {
                        cutSeq = message.getSeq();
                        break;
                    }
                }
            }
            if (cutSeq == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "未找到序号为 " + messageSeq + " 的用户消息（本场共 " + count + " 条）");
            }
            int removed = messageRepository.deleteFromSeq(sessionId, cutSeq);
            log.info("[ChatService] 回溯 session={} 第 {} 条用户消息，删除 {} 条台词（从 seq={} 起）",
                    sessionId, messageSeq, removed, cutSeq);
            return messageRepository.findBySessionIdOrderBySeqAsc(sessionId).stream()
                    .map(SessionMessageView::of)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /** 回溯等生成锁的最长时间（秒）。原版是无期等待，HTTP 请求不能这么干，给个上限。 */
    private static final int GENERATION_LOCK_WAIT_SECONDS = 30;

    // ───────────────────────── 上帝 Agent：选下一个说话的人 ─────────────────────────

    /**
     * 后处理：本轮生成完毕后，上帝 Agent 决定下一个说话者（对齐 {@code post.rs:49-86}）。
     *
     * @return 下一个发言的 role_id；{@code 0} = 交还玩家 / 不继续
     */
    private int postSelect(InterviewSession session, Interviewer speaker, int consecutiveNpcRounds,
                            List<InterviewMessage> history, LlmOptions options) {
        // 轮数到顶先判：原版顺序就是这样（先 budget 再 activate），顺序反了日志会骗人
        if (!godAgent.withinNpcBudget(consecutiveNpcRounds)) {
            log.info("[GodAgent] 连续 {} 轮 NPC 发言，强制返回玩家", consecutiveNpcRounds);
            return 0;
        }
        if (!godAgent.shouldActivate(session)) {
            return 0;
        }
        SpeakerSelector.SpeakerView view = SpeakerSelector.capture(
                godAgent, session, history, interviewerRoleId(speaker), this::npcInfo);
        SpeakerSelector.SpeakerDecision decision = SpeakerSelector.decideNextSpeaker(godAgent, view, options);
        if (decision.roleId() == 0) {
            return 0;
        }
        log.info("[GodAgent] post-select: role_id={}, name={}, reason={}",
                decision.roleId(), decision.name(), decision.reason());
        return decision.roleId();
    }

    /** role_id → 在场面试官展示信息；查不到时 {@code SpeakerSelector} 会用「角色<id>」兜底。 */
    private SpeakerSelector.NpcInfo npcInfo(int roleId) {
        return interviewerRepository.findById((long) roleId)
                .map(interviewer -> new SpeakerSelector.NpcInfo(roleId, interviewer.getName(),
                        interviewer.getSubtitle() == null ? "" : interviewer.getSubtitle(),
                        interviewer.getCardInfo() == null ? "" : interviewer.getCardInfo()))
                .orElse(null);
    }

    /**
     * 设置场上的面试官（对齐 {@code present_role_ids}）。
     *
     * <p>目前没有 HTTP 入口（前端还没有「加第二位面试官」的界面），先留服务层方法：
     * 建会话默认只放主面试官，加人后 {@code shouldActivate} 才可能为真。
     */
    public List<Integer> setPresentRoleIds(Long sessionId, List<Integer> roleIds) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + sessionId));
        StringBuilder joined = new StringBuilder();
        for (Integer roleId : roleIds == null ? List.<Integer>of() : roleIds) {
            if (roleId == null || roleId <= 0) {
                continue;
            }
            if (!interviewerRepository.existsById((long) roleId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "面试官不存在：" + roleId);
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(roleId);
        }
        session.setPresentRoleIds(joined.toString());
        sessionRepository.save(session);
        return godAgent.npcIds(session);
    }

    /**
     * 把「一句原文」变成「一条 assistant 落库 + 一个 ai:reply 事件」。
     *
     * <p>因为 {@code isFinal} 只有等流结束才知道，这里压住最后一句：
     * 下一句到达时才用 {@code isFinal=false} 把它发出去，收尾时用 {@code isFinal=true} 发最后一句。
     */
    private final class ReplyPublisher {

        private final SseEmitter emitter;
        private final InterviewSession session;
        private final Interviewer interviewer;
        private final Integer userSeq;
        private int nextSeq;
        private int published;
        private SentenceSplitter.Sentence pending;
        /** 本轮思考链全文；对齐 generator/mod.rs:698-705，只在最终句挂到台词行。 */
        private String thinking;

        private ReplyPublisher(SseEmitter emitter, InterviewSession session, Interviewer interviewer, int userSeq) {
            this.emitter = emitter;
            this.session = session;
            this.interviewer = interviewer;
            this.userSeq = userSeq;
            this.nextSeq = messageRepository.findMaxSeqBySessionId(session.getId()) + 1;
        }

        private void offer(SentenceSplitter.Sentence sentence) {
            if (pending != null) {
                publish(pending, false);
            }
            pending = sentence;
        }

        private void finish() {
            if (pending != null) {
                publish(pending, true);
                pending = null;
            }
        }

        private int published() {
            return published;
        }

        /** 流结束后把整轮思考链交进来，只有最终句会落它（对齐 {@code if is_final}）。 */
        private void setThinking(String thinking) {
            this.thinking = thinking == null || thinking.isBlank() ? null : thinking;
        }

        private void publish(SentenceSplitter.Sentence sentence, boolean isFinal) {
            String original = sentence.text();
            List<EmotionSegment> segments = symbolParser.parse(original);
            EmotionSegment segment = segments.isEmpty() ? fallbackSegment(original) : segments.get(0);
            if (segment.followingText().isEmpty() && segment.motionText().isEmpty()) {
                // 纯标签/纯空行，没有可显示的正文
                return;
            }

            saveMessage(session.getId(), nextSeq++, InterviewMessage.ROLE_ASSISTANT,
                    segment.followingText(), segment.originalTag(), segment.predicted(), segment.motionText(),
                    interviewerRoleId(interviewer),
                    // 思考链只挂最终句（generator/mod.rs:698-705）
                    isFinal ? thinking : null,
                    interviewer.getName());

            ReplyEvent event = new ReplyEvent(
                    ReplyEvent.TYPE,
                    isFinal,
                    duration(segment.followingText()),
                    segment.followingText(),
                    original,
                    segment.predicted(),
                    segment.originalTag(),
                    segment.motionText(),
                    interviewer.getName(),
                    interviewer.getName(),
                    interviewer.getDisplayRole(),
                    userSeq,
                    interviewerRoleId(interviewer));
            send(emitter, "ai:reply", event);
            published++;
        }

        /** 模型没写 {@code 【情绪】} 时的兜底：剥标签 + 情绪「正常」，保证前端一定有台词。 */
        private EmotionSegment fallbackSegment(String original) {
            String cleaned = original
                    .replaceAll("【[^】]*】", "")
                    .replaceAll("（[^）]*）", "")
                    .replaceAll("<[^>]*>", "")
                    .strip();
            String prediction = EmotionDictionary.getInstance().predict(null);
            return new EmotionSegment(1, null, cleaned, "", "", prediction);
        }
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private void ensureSystemMessage(InterviewSession session, Interviewer interviewer) {
        if (messageRepository.existsBySessionIdAndRole(session.getId(), InterviewMessage.ROLE_SYSTEM)) {
            return;
        }
        log.warn("[ChatService] 会话 {} 缺 system 行，现场补一条", session.getId());
        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc().orElse(null);
        // 只按已绑定的 script_id 取，不再「按岗位猜剧本」：没绑就是自由对话的 system 串
        InterviewScript script = session.getScriptId() == null
                ? null
                : scriptRepository.findById(session.getScriptId()).orElse(null);
        String systemPrompt = buildSystemPrompt(interviewer, profile, script);
        saveMessage(session.getId(), messageRepository.findMaxSeqBySessionId(session.getId()) + 1,
                InterviewMessage.ROLE_SYSTEM, systemPrompt, null, null, null, null);
    }

    /**
     * 落一条台词。
     *
     * @param senderRoleId 对齐 {@code line.sender_role_id}：{@code 0} = 玩家、
     *                     {@code interviewer.id} = 面试官、{@code null} = 旁白 / system
     */
    private void saveMessage(Long sessionId, int seq, String role, String content,
                             String originalTag, String predictedEmotion, String action,
                             Integer senderRoleId) {
        saveMessage(sessionId, seq, role, content, originalTag, predictedEmotion, action,
                senderRoleId, null, null);
    }

    /**
     * 落一条台词（带思考链）。
     *
     * @param thinking 本条回复的思考链，对齐 {@code line.thinking}；非最终句传 {@code null}
     */
    private void saveMessage(Long sessionId, int seq, String role, String content,
                             String originalTag, String predictedEmotion, String action,
                             Integer senderRoleId, String thinking) {
        saveMessage(sessionId, seq, role, content, originalTag, predictedEmotion, action,
                senderRoleId, thinking, null);
    }

    /**
     * 落一条台词（带思考链 + 说话者显示名）。
     *
     * @param thinking 本条回复的思考链，对齐 {@code line.thinking}；非最终句传 {@code null}
     * @param speakerName 这句话在历史里显示成谁说的（对齐 {@code line.display_name}）。
     *                    面试官行传角色名，旁白 / system / 玩家行传 {@code null}
     *                    （玩家的名字由 {@link PlayerIdentityService#currentDisplayName()} 决定）
     */
    private void saveMessage(Long sessionId, int seq, String role, String content,
                             String originalTag, String predictedEmotion, String action,
                             Integer senderRoleId, String thinking, String speakerName) {
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(sessionId);
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setOriginalTag(originalTag);
        message.setPredictedEmotion(predictedEmotion);
        message.setAction(action);
        message.setThinking(thinking);
        message.setMsgType("text");
        message.setSenderRoleId(senderRoleId);
        // 玩家行一律带名字（对齐 memory_builder.rs「永远带名」）：
        // 换身份后历史里会并存多个身份的台词，只靠 role=user 分不清谁说的。
        if (senderRoleId != null && senderRoleId == InterviewMessage.PLAYER_ROLE_ID) {
            message.setIdentityId(playerIdentities.currentIdentityId());
            message.setDisplayName(playerIdentities.currentDisplayName());
        } else if (senderRoleId != null && speakerName != null && !speakerName.isBlank()) {
            // 面试官行同样要带名字（对齐 line.display_name）：历史对话流按 display_name
            // 分组，面试官行留空会被当成旁白渲染成斜体、不冠名。
            // 旁白 / system 行 senderRoleId 为 null，落不到这个分支，保持 NULL。
            message.setDisplayName(speakerName);
        }
        message.setCreatedAt(LocalDateTime.now());
        messageRepository.save(message);
    }

    /** 面试官的 {@code sender_role_id}：{@code interviewer.id}（INT 列，Long 收窄，缺 id 给 null）。 */
    private static Integer interviewerRoleId(Interviewer interviewer) {
        return interviewer == null || interviewer.getId() == null
                ? null : interviewer.getId().intValue();
    }

    /** 展示时长：{@code max(1.2, 字数 * 0.08)}，保留 1 位小数。 */
    static double duration(String message) {
        int length = message == null ? 0 : message.length();
        double raw = Math.max(DURATION_BASE, length * DURATION_PER_CHAR);
        return Math.round(raw * 10) / 10.0;
    }

    /** 拼 system 串：人设 + 面试编排 + 框架句 + 格式 + 示例 + 情绪限制 + 正文要求 + 身份/关系块。 */
    private String buildSystemPrompt(Interviewer interviewer, UserProfile profile, InterviewScript script) {
        String userName = profile == null || profile.getNickname() == null ? "候选人" : profile.getNickname();
        return PromptAssembler.build(
                interviewer.getPersonaPrompt(),
                userName,
                interviewer.getName(),
                interviewer.getExampleDialogue(),
                buildPlan(interviewer, script),
                // chat.reply_count：默认 3 时不追加任何提示（规格 B4-BE §三）
                configService.getInt(ConfigKey.CHAT_REPLY_COUNT),
                buildIdentityBlock(interviewer, userName));
    }

    /**
     * 身份/关系块（{@code player_block}），压在整个 system prompt 末尾。
     *
     * <p>对齐 {@code bootstrap.rs::resolve_player_bootstrap} —— 这是主链路（service.rs:113）
     * 实际用的那条路径。作用是把「正在与你对话的那个『我』是谁」钉死，
     * 声明优先于人设与历史里关于这个人的写法。
     *
     * <p>注意主链路<b>不注入 peers_block</b>：peers_block 只出现在
     * {@code persona.rs::rebuild_persona_line}（换身份 / 存身份后重建「已上场角色」的人设行），
     * 那需要「每个上场角色各持有一条 SYSTEM 行」。本项目是单人面试、一条 system prompt，
     * 没有这个结构，故不拼。buildPeersBlock 保留在 PlayerIdentityPrompt 里备用。
     */
    private String buildIdentityBlock(Interviewer interviewer, String userName) {
        Optional<PlayerIdentity> identity = playerIdentities.current();
        String name = identity.map(PlayerIdentity::displayName).orElse(userName);
        String cardPrompt = identity.map(PlayerIdentity::getPrompt).orElse(null);

        // 老卡兜底（bootstrap.rs:55-60）：身份卡 prompt 为空时，
        // 用角色卡 system_prompt 里抽出的「我的设定」段补上。
        String effectivePrompt;
        if (cardPrompt == null || cardPrompt.trim().isEmpty()) {
            String extracted = PromptAssembler
                    .extractPlayerSetting(interviewer.getPersonaPrompt() == null
                            ? "" : interviewer.getPersonaPrompt())
                    .segment();
            effectivePrompt = extracted == null ? "" : extracted;
        } else {
            effectivePrompt = cardPrompt;
        }
        String prompt = effectivePrompt.trim().isEmpty() ? null : effectivePrompt;

        // 说话者（当前面试官）侧的出厂关系：对齐 runtime_relations::load_effective
        Map<String, String> speakerRelations =
                interviewerRelations.loadEffective(interviewer.getCode(), null);

        // 身份卡上的关系：对齐 runtime_relations::identity_relations_effective（Me 说话者层）
        Map<String, String> identityRelations = identity
                .map(card -> identityPrompt.parseRelations(card.getRelations()))
                .orElseGet(Map::of);

        PlayerIdentityPrompt.RelationView relation = null;
        if (identity.isPresent()) {
            var speaker = new PlayerIdentityPrompt.AiEndpoint(interviewer.getCode());
            var target = new PlayerIdentityPrompt.MeEndpoint(identity.get().getIdentityId());
            // 对齐 bootstrap.rs:62：speaker 关系 + 身份卡关系 + 身份提示词兜底（三级优先）
            relation = identityPrompt.resolveRelation(
                            speaker, target, speakerRelations, identityRelations, prompt)
                    .orElse(null);
        }

        return identityPrompt.buildPlayerBlock(name, prompt, relation);
    }

    /** 把 {@code interview_script} + {@code interview_chapter} 渲染成编排段（规格 B0-BE §3.3）。 */
    private String buildPlan(Interviewer interviewer, InterviewScript script) {
        if (script == null) {
            return null;
        }
        List<InterviewPlan.Stage> stages = new ArrayList<>();
        for (InterviewChapter chapter : chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId())) {
            stages.add(new InterviewPlan.Stage(chapter.getName(), goalOf(chapter)));
        }
        return new InterviewPlan(
                script.getJobCode(),
                interviewer.getName(),
                interviewer.getDisplayRole(),
                stages,
                pacingOf(script),
                "候选人表示想结束，或流程走完时收尾：给出两条具体建议后结束面试。")
                .toSegment();
    }

    /** 章节目标：章节目录名本身就是目标（种子数据里没有单独的 goal 列）。 */
    private String goalOf(InterviewChapter chapter) {
        return chapter.getName() + "阶段，围绕本章主题逐题深入";
    }

    /** 轮次控制文案，取自 {@code interview_script.script_settings}。 */
    private String pacingOf(InterviewScript script) {
        JsonNode settings;
        try {
            settings = objectMapper.readTree(script.getScriptSettings() == null ? "{}" : script.getScriptSettings());
        } catch (Exception e) {
            return "按候选人回答质量控制节奏，答得含糊就追问。";
        }
        int total = settings.path("total_questions").asInt(8);
        int limit = settings.path("time_limit").asInt(40);
        int depth = settings.path("follow_up_depth").asInt(3);
        return "共 " + total + " 题，限时 " + limit + " 分钟，同一话题追问不超过 " + depth + " 次。";
    }

    /** 往 SSE 写一条事件；连接已断时吞掉异常，避免线程里再抛一层。 */
    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("[ChatService] SSE 写入失败（连接可能已断开）：{}", e.toString());
        }
    }
}

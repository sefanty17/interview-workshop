package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.domain.UserProfile;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.dto.SessionMessageView;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmMessage;
import cn.edu.zjut.interview.llm.LlmOptions;
import cn.edu.zjut.interview.prompt.InterviewPlan;
import cn.edu.zjut.interview.prompt.MemoryBuilder;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 已验收的拼装/分句组件，直接复用，不复制逻辑。 */
    private final MemoryBuilder memoryBuilder = new MemoryBuilder();
    private final SymbolParser symbolParser = new SymbolParser();

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
                       ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.interviewerRepository = interviewerRepository;
        this.userProfileRepository = userProfileRepository;
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.llmClient = llmClient;
        this.configService = configService;
        this.objectMapper = objectMapper;
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
        session.setEventSequence(0);
        session.setStartedAt(LocalDateTime.now());
        session.setCreatedAt(LocalDateTime.now());
        session = sessionRepository.save(session);

        // 4. 落 system 消息（seq=0，整串原样存）
        saveMessage(session.getId(), 0, InterviewMessage.ROLE_SYSTEM, systemPrompt, null, null, null, null);

        // 5. 落开场白（seq=1，情绪从 opening 里解析，没有就「正常」）
        String opening = interviewer.getOpening() == null ? "" : interviewer.getOpening();
        List<EmotionSegment> segments = symbolParser.parse(opening);
        EmotionSegment first = segments.isEmpty() ? null : segments.get(0);
        String openingMessage = first == null ? opening.strip() : first.followingText();
        String openingEmotion = first == null
                ? EmotionDictionary.getInstance().predict(null) : first.predicted();
        saveMessage(session.getId(), 1, InterviewMessage.ROLE_ASSISTANT, opening,
                first == null ? null : first.originalTag(), openingEmotion,
                first == null ? null : first.motionText(), interviewerRoleId(interviewer));

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

    private void runTurn(InterviewSession session, Interviewer interviewer, String text, SseEmitter emitter) {
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

            // 5. 读全部消息 → MemoryBuilder
            List<InterviewMessage> history = messageRepository.findBySessionIdOrderBySeqAsc(session.getId());
            List<LlmMessage> messages = memoryBuilder.build(history);

            // 6~8. LlmClient.stream → SentenceSplitter → SymbolParser → 落库 + emit
            // 生成参数每轮从 ConfigService 读一次（规格 B4-BE §三），不在这里直接读 yml
            LlmOptions options = LlmOptions.from(configService);
            ReplyPublisher publisher = new ReplyPublisher(emitter, session, interviewer, userSeq);
            SentenceSplitter splitter = new SentenceSplitter(
                    configService.getInt(ConfigKey.CHAT_MERGE_THRESHOLD),
                    configService.getInt(ConfigKey.CHAT_REPLY_COUNT));
            llmClient.stream(messages, options, chunk -> {
                for (SentenceSplitter.Sentence sentence : splitter.feed(chunk)) {
                    publisher.offer(sentence);
                }
            });
            // 收尾句：SentenceSplitter 的 finish() 给出最后一段
            for (SentenceSplitter.Sentence sentence : splitter.finish()) {
                publisher.offer(sentence);
            }
            publisher.finish();

            // 9. 流结束：status:reset
            send(emitter, "status:reset", Map.of());
            emitter.complete();
            log.info("[ChatService] 会话 {} 本轮结束：userSeq={} assistant={} 条",
                    session.getId(), userSeq, publisher.published());
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
        }
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
                    interviewerRoleId(interviewer));

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
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(sessionId);
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setOriginalTag(originalTag);
        message.setPredictedEmotion(predictedEmotion);
        message.setAction(action);
        message.setMsgType("text");
        message.setSenderRoleId(senderRoleId);
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

    /** 拼 system 串：人设 + 面试编排 + 框架句 + 格式 + 示例 + 情绪限制 + 正文要求。 */
    private String buildSystemPrompt(Interviewer interviewer, UserProfile profile, InterviewScript script) {
        String userName = profile == null || profile.getNickname() == null ? "候选人" : profile.getNickname();
        return PromptAssembler.build(
                interviewer.getPersonaPrompt(),
                userName,
                interviewer.getName(),
                interviewer.getExampleDialogue(),
                buildPlan(interviewer, script),
                // chat.reply_count：默认 3 时不追加任何提示（规格 B4-BE §三）
                configService.getInt(ConfigKey.CHAT_REPLY_COUNT));
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

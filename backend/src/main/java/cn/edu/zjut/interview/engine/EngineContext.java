package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个事件的执行上下文，对应 Rust 的 {@code ScriptContext}（{@code events/mod.rs}）。
 *
 * <p>Rust 用一把 {@code game_status} 大锁承载 session / 变量 / 台词表；Java 侧改成
 * 「一次事件一个上下文对象」：{@code session} 与可变变量表 {@code vars} 由引擎持有，
 * 事件通过 {@link #saveMessage} / {@link #vars()} 回写，引擎在每个事件结束后统一落库。
 *
 * <p>在场角色集合 {@link #presentRoles()} 对应 {@code game_status.present_role_ids}
 * （{@code game_status.rs:562 onstage_role / 569 offstage_role}）：一开始只有主角，
 * {@code modify_character} 的 {@code show_character} / {@code hide_character} 增删。
 */
public class EngineContext {

    /** 主角的剧本内标识；原版同名常量，指向 {@code game_status.main_role_id}。 */
    public static final String MAIN_ROLE = "MAIN";

    private final InterviewSession session;
    private final JsonNode event;
    private final EventSink sink;
    private final InterviewMessageRepository messageRepository;
    private final InterviewChapterRepository chapterRepository;
    private final InterviewerRepository interviewerRepository;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final String playerName;
    /** 玩家台词属于哪个身份（对齐 {@code line.identity_id}）；不知道就留 {@code null}。 */
    private final String identityId;
    private final ObjectNode vars;
    private final String chapterCode;
    private final Set<String> presentRoles;
    /** 本轮生成的可调项（台词合并阈值 / 回复段数），由引擎按后端设置填。 */
    private ChatTuning tuning = ChatTuning.DEFAULTS;

    /** 不带在场集合的构造：默认舞台上只有主角（{@code {"MAIN"}}）。 */
    public EngineContext(InterviewSession session,
                         JsonNode event,
                         EventSink sink,
                         InterviewMessageRepository messageRepository,
                         InterviewChapterRepository chapterRepository,
                         InterviewerRepository interviewerRepository,
                         LlmClient llmClient,
                         ObjectMapper objectMapper,
                         String playerName,
                         ObjectNode vars,
                         String chapterCode) {
        this(session, event, sink, messageRepository, chapterRepository, interviewerRepository,
                llmClient, objectMapper, playerName, vars, chapterCode, defaultPresentRoles(), null);
    }

    public EngineContext(InterviewSession session,
                         JsonNode event,
                         EventSink sink,
                         InterviewMessageRepository messageRepository,
                         InterviewChapterRepository chapterRepository,
                         InterviewerRepository interviewerRepository,
                         LlmClient llmClient,
                         ObjectMapper objectMapper,
                         String playerName,
                         ObjectNode vars,
                         String chapterCode,
                         Set<String> presentRoles) {
        this(session, event, sink, messageRepository, chapterRepository, interviewerRepository,
                llmClient, objectMapper, playerName, vars, chapterCode, presentRoles, null);
    }

    public EngineContext(InterviewSession session,
                         JsonNode event,
                         EventSink sink,
                         InterviewMessageRepository messageRepository,
                         InterviewChapterRepository chapterRepository,
                         InterviewerRepository interviewerRepository,
                         LlmClient llmClient,
                         ObjectMapper objectMapper,
                         String playerName,
                         ObjectNode vars,
                         String chapterCode,
                         Set<String> presentRoles,
                         String identityId) {
        this.session = session;
        this.event = event;
        this.sink = sink;
        this.messageRepository = messageRepository;
        this.chapterRepository = chapterRepository;
        this.interviewerRepository = interviewerRepository;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.playerName = playerName;
        this.vars = vars;
        this.chapterCode = chapterCode;
        this.presentRoles = presentRoles == null ? defaultPresentRoles() : presentRoles;
        this.identityId = identityId;
    }

    /** 舞台初始状态：只有主角在场。 */
    public static Set<String> defaultPresentRoles() {
        Set<String> roles = new LinkedHashSet<>();
        roles.add(MAIN_ROLE);
        return roles;
    }

    public InterviewSession session() {
        return session;
    }

    public Long sessionId() {
        return session.getId();
    }

    public JsonNode event() {
        return event;
    }

    public EventSink sink() {
        return sink;
    }

    public InterviewMessageRepository messageRepository() {
        return messageRepository;
    }

    public InterviewChapterRepository chapterRepository() {
        return chapterRepository;
    }

    public InterviewerRepository interviewerRepository() {
        return interviewerRepository;
    }

    public LlmClient llmClient() {
        return llmClient;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public String playerName() {
        return playerName;
    }

    /** 本轮生成的可调项（没设置过就是 {@link ChatTuning#DEFAULTS}）。 */
    public ChatTuning tuning() {
        return tuning == null ? ChatTuning.DEFAULTS : tuning;
    }

    /** 由 {@code InterviewEngine} 在开跑前按后端设置填一次。 */
    public void tuning(ChatTuning tuning) {
        this.tuning = tuning == null ? ChatTuning.DEFAULTS : tuning;
    }

    /** 可变剧本变量表（{@code script_status.vars}）。 */
    public ObjectNode vars() {
        return vars;
    }

    /** 当前章节 code，落库时写进 {@code interview_message.stage_code}。 */
    public String chapterCode() {
        return chapterCode;
    }

    /** 事件 JSON 里的某个文本字段，缺省空串。 */
    public String text(String field) {
        return event == null ? "" : event.path(field).asText("");
    }

    /** 当前章节实体（ai_dialogue 需要章节 code 落库，已由 {@link #chapterCode()} 提供，这里给测试/扩展留口）。 */
    public InterviewChapter chapter() {
        if (session.getScriptId() == null || chapterCode == null) {
            return null;
        }
        return chapterRepository.findByScriptIdAndCode(session.getScriptId(), chapterCode).orElse(null);
    }

    /** 当前面试官（拿 name / displayRole 填 reply 事件）。 */
    public Interviewer interviewer() {
        return session.getInterviewerId() == null
                ? null
                : interviewerRepository.findById(session.getInterviewerId()).orElse(null);
    }

    /**
     * 在场角色集合（可写），对应 {@code game_status.present_role_ids}。
     *
     * <p>元素是剧本里的角色名（{@code MAIN} 或 {@code characters/} 下的目录名），
     * 对外推送前用 {@link #roleId(String)} 换成实际标识。
     */
    public Set<String> presentRoles() {
        return presentRoles;
    }

    /** 在场角色对外标识（{@code MAIN} → 面试官 code），推给前端渲染多槽位。 */
    public List<String> presentRoleIds() {
        return presentRoles.stream().map(this::roleId).toList();
    }

    /**
     * 剧本角色名 → 角色标识：{@code MAIN} 解析成当前面试官（原版 {@code main_role_id}，
     * 见 {@code script_function.rs:30 get_role}），其余名字原样返回。
     */
    public String roleId(String character) {
        if (character == null || character.isEmpty()) {
            return null;
        }
        if (!MAIN_ROLE.equals(character)) {
            return character;
        }
        Interviewer interviewer = interviewer();
        return interviewer == null || interviewer.getCode() == null ? MAIN_ROLE : interviewer.getCode();
    }

    /**
     * 台词的 {@code sender_role_id}（对齐 {@code line.rs}）：{@code 0} = 玩家、
     * 角色 id = 说话的角色、{@code null} = 旁白 / system。
     *
     * <p>引擎把旁白与剧情提示借用 {@code role=user} 落库（{@code msg_type} 区分），
     * 但原版这两类的 {@code sender_role_id} 是 {@code None}，所以这里要排掉。
     */
    public Integer senderRoleId(String role, String msgType) {
        if (InterviewMessage.ROLE_USER.equals(role)) {
            return "narration".equals(msgType) || "plot".equals(msgType)
                    ? null : InterviewMessage.PLAYER_ROLE_ID;
        }
        if (InterviewMessage.ROLE_ASSISTANT.equals(role)) {
            Interviewer interviewer = interviewer();
            return interviewer == null || interviewer.getId() == null
                    ? null : interviewer.getId().intValue();
        }
        return null;
    }

    /** 下一条台词的 seq：当前最大值 + 1。 */
    public int nextSeq() {
        return messageRepository.findMaxSeqBySessionId(session.getId()) + 1;
    }

    /**
     * 落一条台词，返回它的 seq。
     *
     * @param msgType {@code text} / {@code plot} / {@code narration}
     */
    public int saveMessage(String role, String content, String msgType,
                           String originalTag, String predictedEmotion, String action) {
        int seq = nextSeq();
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(session.getId());
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setOriginalTag(originalTag);
        message.setPredictedEmotion(predictedEmotion);
        message.setAction(action);
        message.setMsgType(msgType == null ? "text" : msgType);
        message.setStageCode(chapterCode);
        Integer roleId = senderRoleId(role, msgType);
        message.setSenderRoleId(roleId);
        // 玩家行一律带名字（对齐 memory_builder.rs「永远带名」）；旁白/剧情借 user 落库，不带
        if (roleId != null && roleId == InterviewMessage.PLAYER_ROLE_ID) {
            message.setIdentityId(identityId);
            message.setDisplayName(playerName == null || playerName.isBlank()
                    ? "用户" : playerName.trim());
        }
        message.setCreatedAt(LocalDateTime.now());
        messageRepository.save(message);
        return seq;
    }
}

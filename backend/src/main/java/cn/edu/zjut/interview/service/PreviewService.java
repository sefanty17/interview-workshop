package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.PreviewSessionView;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import cn.edu.zjut.interview.editor.EditorNotFoundException;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 剧本编辑器的试玩（规格 E5b §1.3），对应 LingChat 原版的
 * {@code components/script-editor/preview/PreviewStage.vue} 与
 * {@code api/script_editor/commands.rs} 的 {@code editor_start_preview} /
 * {@code editor_stop_preview} / {@code PreviewSession}。
 *
 * <p><b>与原版的实现差异（不是功能缺失）</b>：原版试玩与正式游玩共用同一个全局
 * {@code GameStatus}，所以必须「进来搭台、出去还原」——{@code PreviewSession} 里存
 * {@code line_len / generation / scene} 快照，退出时逐项写回。本项目没有全局共享会话状态，
 * 每个场次的状态天然隔离在 {@code interview_session} 一行里，于是这一套退化成
 * <b>「专用试玩场次（preview=true）+ 退出即删」</b>：玩家的真实场次一个字节都没被碰过，
 * 与原版「试玩不留痕」的目标一致，实现更简单，因此原版 {@code PreviewSession} 的
 * 快照字段一个都不用照抄。
 *
 * <p><b>不重复造轮子</b>：开场串（seq=0 的 system 提示）与开场白（seq=1）一律复用
 * {@link ChatService#createSession}，这里绝不复制 {@code buildSystemPrompt} 那套拼装逻辑——
 * 试玩与正式游玩必须走同一条落库路径，否则两边的人设/编排会各自漂移。
 *
 * <p>「走真引擎、真渲染层」由前端保证：试玩场次建好后，前端用同一个
 * {@code POST /api/sessions/{id}/script/start} 与同一套 SSE 事件流推进，
 * 后端不为试玩另写解释器。「与正式游玩只有两点差别」也落在这一层：
 * 试玩场次不计分（不会调 {@code finishNow}），也不出现在场次列表（见
 * {@code InterviewSessionRepository.findAllByPreviewFalseOrderByCreatedAtDesc}）。
 */
@Service
public class PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);

    /** 试玩场次标题前缀：一眼能认出这是临时场次（原版顶栏「试玩中」badge 的等价物）。 */
    private static final String TITLE_PREFIX = "试玩 · ";

    /** 剧本没填岗位时用的兜底岗位，与 {@link ChatService#createSession} 的缺省一致。 */
    private static final String DEFAULT_JOB_CODE = "java";

    /** 剧本没绑面试官时的兜底面试官编码。 */
    private static final String DEFAULT_INTERVIEWER_CODE = "strict";

    private final ChatService chatService;
    private final InterviewScriptRepository scriptRepository;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewerRepository interviewerRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewChapterRepository chapterRepository;

    public PreviewService(ChatService chatService,
                          InterviewScriptRepository scriptRepository,
                          InterviewSessionRepository sessionRepository,
                          InterviewerRepository interviewerRepository,
                          InterviewMessageRepository messageRepository,
                          InterviewChapterRepository chapterRepository) {
        this.chatService = chatService;
        this.scriptRepository = scriptRepository;
        this.sessionRepository = sessionRepository;
        this.interviewerRepository = interviewerRepository;
        this.messageRepository = messageRepository;
        this.chapterRepository = chapterRepository;
    }

    /**
     * 建一个试玩场次（原版 {@code editor_start_preview}）。
     *
     * <p>步骤顺序固定，见规格 E5b §1.3：先清掉上一轮残留 → 查剧本 → 定面试官/岗位 →
     * 复用 {@link ChatService#createSession} 落 system 串与开场白 → 把这一行改成试玩场次。
     *
     * @param scriptKey   剧本编码（{@code interview_script.code}）
     * @param fromChapter 起始章节 id；null/空白表示从 {@code intro_chapter} 开始
     *                    （与原版 {@code from_chapter: Option<String>} 同语义）
     * @return 前端试玩舞台要的场次信息
     * @throws EditorNotFoundException 剧本不存在（controller 转 404）
     */
    public PreviewSessionView start(String scriptKey, String fromChapter) {
        // 1. 先清场（规格第 5 步，但必须发生在建场之前）：上一次试玩可能是关浏览器走掉的，
        //    场次会残留，不清就会一轮轮堆积。
        discardLeftovers();

        // 2. 剧本必须存在——查不到当作编辑器侧的目标不存在，与其它 editor_* 命令一致
        InterviewScript script = scriptRepository.findByCode(scriptKey)
                .orElseThrow(() -> new EditorNotFoundException("剧本不存在: '" + scriptKey + "'"));

        // 3. 起始章节必须真实存在（原版 editor_start_preview 的
        //    `paths::resolve_chapter_file(&dir, &from, true)?`）：等到开演再报 400 的话，
        //    作者看到的是一个空场子，排查成本高；这里在开场前就拒掉。
        if (fromChapter != null && !fromChapter.isBlank()
                && chapterRepository.findByScriptIdAndCode(script.getId(), fromChapter).isEmpty()) {
            throw new EditorBadRequestException("剧本没有这一章：" + fromChapter);
        }

        // 4. 面试官：剧本绑定的 → strict → 库里第一个，逐级兜底，保证试玩一定能起场
        Interviewer interviewer = resolveInterviewer(script);
        String jobCode = script.getJobCode() == null || script.getJobCode().isBlank()
                ? DEFAULT_JOB_CODE : script.getJobCode();

        // 5. 复用建会话的完整五步：开场串 / 开场白 / 落库一行都不重写
        CreateSessionResponse created = chatService.createSession(
                new CreateSessionRequest(interviewer.getCode(), jobCode, script.getCode()));

        // 6. 把刚建的普通场次改成试玩场次：不计分、不进列表、退出即删
        InterviewSession session = sessionRepository.findById(created.sessionId())
                .orElseThrow(() -> new IllegalStateException("刚建的场次读不到：" + created.sessionId()));
        session.setPreview(Boolean.TRUE);
        session.setTitle(TITLE_PREFIX + script.getScriptName());
        session.setCurrentChapter(fromChapter == null || fromChapter.isBlank()
                ? script.getIntroChapter() : fromChapter);
        // 开演前进度归零，与 ScriptPlayController.start 的口径一致（引擎从章节首事件开始）
        session.setEventSequence(0);
        sessionRepository.save(session);

        log.info("[PreviewService] 建试玩场次 id={} script={} fromChapter={}",
                session.getId(), script.getCode(), session.getCurrentChapter());

        return new PreviewSessionView(
                session.getId(),
                script.getCode(),
                fromChapter,
                interviewer.getCode(),
                interviewer.getName(),
                jobCode);
    }

    /**
     * 删掉一个试玩场次（原版 {@code editor_stop_preview} 的「还原」）。
     *
     * <p>原版还原的是被覆盖的 {@code GameStatus} 字段；本项目还原 = 把这一行连同它的台词
     * 一起删干净，真实场次本来就没被碰过，所以这里<b>只允许删试玩场次</b>：
     * {@code preview != true} 的场次直接返回，防止误调把玩家的真实面试记录删了。
     *
     * @param sessionId 场次 id；null 或不存在的场次静默返回（退出试玩这条路不该 500）
     */
    public void discard(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        if (!Boolean.TRUE.equals(session.isPreview())) {
            log.warn("[PreviewService] 场次 {} 不是试玩场次，拒绝删除（保护真实记录）", sessionId);
            return;
        }
        // messageRepository 没有 deleteBySessionId：用已有的按 seq 取全量的方法再 deleteAll，
        // 顺序照原版「先删台词再删 save 行」，避免外键悬空。
        List<InterviewMessage> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        if (!messages.isEmpty()) {
            messageRepository.deleteAll(messages);
        }
        sessionRepository.deleteById(sessionId);
        log.info("[PreviewService] 删除试玩场次 id={}（连带 {} 条台词）", sessionId, messages.size());
    }

    /** 清掉所有残留的试玩场次（规格第 5 步：「试玩场次总数恒为 1」）。 */
    private void discardLeftovers() {
        for (InterviewSession leftover : sessionRepository.findAllByPreviewTrueOrderByCreatedAtDesc()) {
            discard(leftover.getId());
        }
    }

    /** 面试官逐级兜底：剧本绑定 → {@code strict} → 库里第一个。 */
    private Interviewer resolveInterviewer(InterviewScript script) {
        if (script.getInterviewerId() != null) {
            Interviewer bound = interviewerRepository.findById(script.getInterviewerId()).orElse(null);
            if (bound != null) {
                return bound;
            }
        }
        return interviewerRepository.findByCode(DEFAULT_INTERVIEWER_CODE)
                .or(() -> interviewerRepository.findFirstByOrderByIdAsc())
                .orElseThrow(() -> new IllegalStateException("库里没有任何面试官，无法建试玩场次"));
    }
}

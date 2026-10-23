package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.PreviewSessionView;
import cn.edu.zjut.interview.dto.SessionSummaryView;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.service.ChatService;
import cn.edu.zjut.interview.service.PreviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E5b §1.4：试玩场次的建、删、排除。
 *
 * <p>试玩在原版是「搭台 + 还原」（{@code editor_start_preview} / {@code editor_stop_preview}），
 * 本项目退化为「preview=true 的专用场次 + 退出即删」，所以这里断言的是这几条不变量：
 * 试玩场次带标记且标题可辨、开场串与开场白仍由 {@code ChatService.createSession} 落、
 * 起始章节生效、试玩场次恒为 1 场、非试玩场次删不掉、场次列表不含试玩。
 *
 * <p>用 {@code @SpringBootTest} + test profile（内存库），不碰开发库
 * {@code backend/data/interview.mv.db}；用例自己造场次、{@link AfterEach} 统一清场。
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServiceTest {

    /** 种子剧本：绑 strict 面试官，章节 intro / basic / basic_more / deep / scenario / close。 */
    private static final String SCRIPT = "java-strict";

    @Autowired
    private PreviewService previewService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private InterviewEngine engine;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private InterviewMessageRepository messageRepository;

    @Autowired
    private InterviewScriptRepository scriptRepository;

    /** 本类造出来的「真实场次」（非试玩），{@link #cleanup()} 只清这几条。 */
    private final List<Long> createdRealSessions = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        // 试玩场次：走 discard（连带台词）
        for (InterviewSession leftover : sessionRepository.findAllByPreviewTrueOrderByCreatedAtDesc()) {
            previewService.discard(leftover.getId());
        }
        // 用例自己造的「真实场次」：只删本类建的那几条，别碰别的测试类的数据
        for (Long id : createdRealSessions) {
            if (sessionRepository.findById(id).isPresent()) {
                dropSession(id);
            }
        }
        createdRealSessions.clear();
    }

    // ---- 1：preview 标记 + 标题 ----

    @Test
    void 试玩场次带preview标记且标题以试玩开头() {
        PreviewSessionView view = previewService.start(SCRIPT, null);

        InterviewSession session = sessionRepository.findById(view.sessionId()).orElseThrow();
        assertEquals(Boolean.TRUE, session.isPreview(), "试玩场次必须 preview=true");
        assertTrue(session.getTitle().startsWith("试玩 · "), "标题必须能一眼认出是试玩：" + session.getTitle());
        assertEquals("试玩 · " + scriptRepository.findByCode(SCRIPT).orElseThrow().getScriptName(),
                session.getTitle(), "标题 = 试玩 · 剧本名");

        // 开场串与开场白仍由 ChatService.createSession 落：seq=0 system、seq=1 开场白
        List<InterviewMessage> messages = messageRepository.findBySessionIdOrderBySeqAsc(view.sessionId());
        assertEquals(2, messages.size(), "试玩场次也要有 system 串 + 开场白两条");
        assertEquals(InterviewMessage.ROLE_SYSTEM, messages.get(0).getRole());
        assertFalse(messages.get(0).getContent().isBlank(), "system 串不能是空串");
        assertEquals(InterviewMessage.ROLE_ASSISTANT, messages.get(1).getRole());

        // 面试官/岗位随视图返回，前端舞台按它解析人设
        assertEquals("strict", view.interviewerCode());
        assertNotNull(view.interviewerName());
        assertEquals("java", view.jobCode());
        assertEquals(SCRIPT, view.scriptCode());
    }

    // ---- 2：场次可查 + 剧本绑定 + 起始章节 ----

    @Test
    void 起始章节非空时生效为空时回到开场章() {
        InterviewScript script = scriptRepository.findByCode(SCRIPT).orElseThrow();

        PreviewSessionView withChapter = previewService.start(SCRIPT, "basic");
        InterviewSession session = sessionRepository.findById(withChapter.sessionId()).orElseThrow();
        assertEquals(script.getId(), session.getScriptId(), "场次必须指向该剧本");
        assertEquals("basic", session.getCurrentChapter(), "指定了 fromChapter 就从这一章开演");
        assertEquals("basic", withChapter.fromChapter(), "视图把起始章节原样带回前端");
        assertEquals(0, session.getEventSequence(), "开演前进度归零");

        PreviewSessionView noChapter = previewService.start(SCRIPT, null);
        InterviewSession fallback = sessionRepository.findById(noChapter.sessionId()).orElseThrow();
        assertEquals(script.getIntroChapter(), fallback.getCurrentChapter(),
                "fromChapter 为空时回到 intro_chapter");
        assertFalse(withChapter.sessionId().equals(noChapter.sessionId()), "两次试玩是两行独立场次");
    }

    // ---- 3：建之前清场 ----

    @Test
    void 起始章节不存在时当场拒绝() {
        // 原版 editor_start_preview 的 resolve_chapter_file 在开场前就报错：
        // 不拒掉的话作者点开的是一个空场子，很难自己想到是章节名打错了
        EditorBadRequestException e = assertThrows(EditorBadRequestException.class,
                () -> previewService.start(SCRIPT, "no-such-chapter"));
        assertTrue(e.getMessage().contains("没有这一章"), "错误信息要指出是章节问题：" + e.getMessage());
        assertTrue(sessionRepository.findAllByPreviewTrueOrderByCreatedAtDesc().isEmpty(),
                "被拒之后不该留下半拉试玩场次");
    }

    @Test
    void 第二次试玩会把第一次的场次删掉() {
        Long first = previewService.start(SCRIPT, "basic").sessionId();
        assertTrue(sessionRepository.findById(first).isPresent(), "第一次试玩场次此时应存在");

        Long second = previewService.start(SCRIPT, "deep").sessionId();

        assertFalse(sessionRepository.findById(first).isPresent(), "上一轮试玩场次应被清掉");
        assertTrue(sessionRepository.findById(second).isPresent(), "新试玩场次应在");
        assertEquals(1, sessionRepository.findAllByPreviewTrueOrderByCreatedAtDesc().size(),
                "试玩场次总数恒为 1");
        assertTrue(messageRepository.findBySessionIdOrderBySeqAsc(first).isEmpty(),
                "被清掉的场次不该留下台词（否则外键悬空）");
    }

    // ---- 4：discard 对真实场次是 no-op ----

    @Test
    void discard对非试玩场次不删() {
        CreateSessionResponse created = chatService.createSession(
                new CreateSessionRequest("strict", "java", null));
        Long realId = created.sessionId();
        createdRealSessions.add(realId);

        previewService.discard(realId);

        assertTrue(sessionRepository.findById(realId).isPresent(), "真实场次不能被试玩的 discard 删掉");
        assertEquals(2, messageRepository.findBySessionIdOrderBySeqAsc(realId).size(),
                "真实场次的台词也不能被删");
        // 不存在的 id 同样是静默 no-op（退出试玩这条路不该 500）
        previewService.discard(-1L);
    }

    // ---- 5：场次列表排除试玩 ----

    @Test
    void 场次列表不含试玩场次() {
        Long previewId = previewService.start(SCRIPT, "basic").sessionId();

        List<Long> all = engine.list(null).stream().map(SessionSummaryView::id).toList();
        assertFalse(all.contains(previewId), "不带过滤的场次列表不该出现试玩场次");

        List<Long> running = engine.list("running").stream().map(SessionSummaryView::id).toList();
        assertFalse(running.contains(previewId), "按状态过滤的场次列表也不该出现试玩场次");

        // 仓库层同样过滤掉（引擎就是靠这两条派生查询）
        assertTrue(sessionRepository.findAllByPreviewFalseOrderByCreatedAtDesc().stream()
                        .noneMatch(s -> s.getId().equals(previewId)),
                "findAllByPreviewFalseOrderByCreatedAtDesc 应排除试玩场次");
        assertTrue(sessionRepository.findByStatusAndPreviewFalseOrderByCreatedAtDesc("running").stream()
                        .noneMatch(s -> s.getId().equals(previewId)),
                "findByStatusAndPreviewFalseOrderByCreatedAtDesc 应排除试玩场次");
    }

    // ---- 6：剧本不存在 ----

    @Test
    void 剧本不存在时抛NotFound() {
        assertThrows(EditorNotFoundException.class, () -> previewService.start("e5b-no-such-script", null));
    }

    /** 删场次时先删台词（与 {@code PreviewService.discard} 同顺序），测试清理专用。 */
    private void dropSession(Long sessionId) {
        List<InterviewMessage> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        if (!messages.isEmpty()) {
            messageRepository.deleteAll(messages);
        }
        sessionRepository.deleteById(sessionId);
    }
}

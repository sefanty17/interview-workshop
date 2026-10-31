package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.SessionDetailView;
import cn.edu.zjut.interview.dto.SessionFinishView;
import cn.edu.zjut.interview.dto.SessionMessageView;
import cn.edu.zjut.interview.dto.SessionSummaryView;
import cn.edu.zjut.interview.engine.EngineExecutor;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.engine.SseEventSink;
import cn.edu.zjut.interview.repository.InterviewEvaluationDimensionRepository;
import cn.edu.zjut.interview.repository.InterviewEvaluationRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 场次接口（规格 B3-BE §1.1 / §1.3，规格 B5 §6.1）。
 *
 * <p>建会话 / 查消息仍走 {@link ChatService}；{@code advance} 起剧本引擎推进到下一个阻塞点，
 * SSE 事件名沿用 LingChat 原名（{@code script:chapter-change / script:progress / script:narration /
 * ai:reply / script:input / script:modify-character / script:end / ai:error}）。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    /** SSE 连接的兜底超时，比 LLM 的 60s 长一截（同 ChatService）。 */
    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    private final ChatService chatService;
    private final InterviewEngine engine;
    private final EngineExecutor engineExecutor;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final InterviewEvaluationDimensionRepository dimensionRepository;

    public SessionController(ChatService chatService,
                             InterviewEngine engine,
                             EngineExecutor engineExecutor,
                             InterviewSessionRepository sessionRepository,
                             InterviewMessageRepository messageRepository,
                             InterviewEvaluationRepository evaluationRepository,
                             InterviewEvaluationDimensionRepository dimensionRepository) {
        this.chatService = chatService;
        this.engine = engine;
        this.engineExecutor = engineExecutor;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.evaluationRepository = evaluationRepository;
        this.dimensionRepository = dimensionRepository;
    }

    @PostMapping
    public CreateSessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        return chatService.createSession(request);
    }

    @GetMapping("/{id}")
    public SessionDetailView detail(@PathVariable Long id) {
        return engine.detail(id);
    }

    @GetMapping
    public List<SessionSummaryView> list(@RequestParam(required = false) String status) {
        return engine.list(status);
    }

    /**
     * 删除一场面试：先清台词、评估维度、评估，再删场次本身，避免外键悬空。
     * 记录页的单个删除 / 批量删除都走这里。
     */
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        if (!sessionRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "场次不存在");
        }
        dimensionRepository.deleteBySessionId(id);
        evaluationRepository.deleteBySessionId(id);
        List<InterviewMessage> messages = messageRepository.findBySessionIdOrderBySeqAsc(id);
        if (!messages.isEmpty()) {
            messageRepository.deleteAll(messages);
        }
        sessionRepository.deleteById(id);
        log.info("[SessionController] 已删除场次 {}（连带 {} 条台词）", id, messages.size());
    }

    /**
     * 台词列表（历史对话流的数据源，对齐 {@code convert_init_lines} 的落库顺序）。
     *
     * <p>{@code userMessageSeq} 在这里统一编号：按 {@code seq} 升序扫，每遇到 {@code role=user}
     * 就 +1 并写回该行。后端不存这一列（存了会和落库时序打架），编号规则与
     * {@code GameMessage.user_message_seq} 一致。
     */
    @GetMapping("/{id}/messages")
    public List<SessionMessageView> messages(@PathVariable Long id,
                                             @RequestParam(defaultValue = "0") int afterSeq) {
        return withUserMessageSeq(chatService.listMessages(id, afterSeq));
    }

    /** 给 {@code role=user} 的行按 seq 升序编号 {@code userMessageSeq}（1-based）。 */
    private List<SessionMessageView> withUserMessageSeq(List<SessionMessageView> source) {
        List<SessionMessageView> views = new ArrayList<>(source);
        int userSeq = 0;
        for (int i = 0; i < views.size(); i++) {
            SessionMessageView view = views.get(i);
            if ("user".equals(view.role())) {
                userSeq += 1;
                views.set(i, view.withUserMessageSeq(userSeq));
            }
        }
        return views;
    }

    /**
     * 回溯对话：删掉第 {@code seq} 条用户消息及其之后的全部台词，返回剩余台词。
     *
     * <p>对齐 {@code api/chat.rs:502 rollback_conversation}。{@code userMessageSeq} 的
     * 重新编号走 {@link #messages} 的同一套逻辑 —— 截断后序号必须从 1 连续重排，
     * 否则下一次回溯会定位到错误的那条。
     */
    @PostMapping("/{id}/rollback")
    public List<SessionMessageView> rollback(@PathVariable Long id, @RequestParam int seq) {
        return withUserMessageSeq(chatService.rollback(id, seq));
    }

    /** 推进剧本到下一个阻塞点（input / free_dialogue / 结束）。 */
    @PostMapping(value = "/{id}/advance", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter advance(@PathVariable Long id) {
        engine.ensureExists(id);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseEventSink sink = new SseEventSink(emitter);
        engineExecutor.submit(() -> {
            try {
                engine.run(id, sink);
                emitter.complete();
            } catch (Exception e) {
                log.warn("[SessionController] 会话 {} 推进失败：{}", id, e.toString());
                sink.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PostMapping("/{id}/finish")
    public SessionFinishView finish(@PathVariable Long id) {
        return engine.finishNow(id);
    }
}

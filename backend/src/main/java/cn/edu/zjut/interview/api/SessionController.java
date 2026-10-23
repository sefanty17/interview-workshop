package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.SessionDetailView;
import cn.edu.zjut.interview.dto.SessionFinishView;
import cn.edu.zjut.interview.dto.SessionMessageView;
import cn.edu.zjut.interview.dto.SessionSummaryView;
import cn.edu.zjut.interview.engine.EngineExecutor;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.engine.SseEventSink;
import cn.edu.zjut.interview.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    public SessionController(ChatService chatService, InterviewEngine engine, EngineExecutor engineExecutor) {
        this.chatService = chatService;
        this.engine = engine;
        this.engineExecutor = engineExecutor;
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

    @GetMapping("/{id}/messages")
    public List<SessionMessageView> messages(@PathVariable Long id,
                                             @RequestParam(defaultValue = "0") int afterSeq) {
        return chatService.listMessages(id, afterSeq);
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

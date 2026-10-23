package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.ScriptChoiceRequest;
import cn.edu.zjut.interview.dto.ScriptStartRequest;
import cn.edu.zjut.interview.dto.SendMessageRequest;
import cn.edu.zjut.interview.engine.EngineExecutor;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.engine.ScriptInputRejectedException;
import cn.edu.zjut.interview.engine.SseEventSink;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 剧本接口（规格 F-1 §B3），对应原版 {@code api/script.rs} 的三个入口：
 * {@code start_script} / {@code script_submit_input} / {@code script_submit_choice}，
 * 外加一个 {@code stop_script} 回到自由对话。
 *
 * <p>四个端点都不碰自由对话链路：{@code /messages} 归 {@link ChatController}，
 * 这里只推进剧本。SSE 事件名沿用 LingChat 原名（{@code script:* / ai:reply / ai:error}），
 * 由 {@link SseEventSink} 统一发出。
 *
 * <p>「拒绝」语义（原版 {@code script.rs:217-244}）：输入与当前阻塞点不匹配时抛
 * {@link ScriptInputRejectedException}，这里转成 SSE {@code ai:error}，<b>不落用户台词、
 * 不推进事件下标</b>——对前端而言等价于原版把 {@code Err} 回给它。
 */
@RestController
@RequestMapping("/api/sessions")
public class ScriptPlayController {

    private static final Logger log = LoggerFactory.getLogger(ScriptPlayController.class);

    /** SSE 连接的兜底超时，与 {@link ChatController} / {@code ChatService} 同规格。 */
    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    /** {@code /script/stop} 之后的空变量表（与原版 {@code stop_script} 清空 game_status 一致）。 */
    private static final String EMPTY_VARS = "{}";

    private final InterviewEngine engine;
    private final EngineExecutor engineExecutor;
    private final InterviewScriptRepository scriptRepository;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewChapterRepository chapterRepository;

    public ScriptPlayController(InterviewEngine engine,
                                EngineExecutor engineExecutor,
                                InterviewScriptRepository scriptRepository,
                                InterviewSessionRepository sessionRepository,
                                InterviewChapterRepository chapterRepository) {
        this.engine = engine;
        this.engineExecutor = engineExecutor;
        this.scriptRepository = scriptRepository;
        this.sessionRepository = sessionRepository;
        this.chapterRepository = chapterRepository;
    }

    /**
     * 开演：绑定剧本 → 进度归零 → 从章节首事件跑到第一个阻塞点。
     *
     * <p>找不到剧本在开流之前就 400，避免把参数错误变成流里的 error 事件。
     */
    @PostMapping(value = "/{id}/script/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter start(@PathVariable Long id, @RequestBody(required = false) ScriptStartRequest request) {
        engine.ensureExists(id);
        String scriptCode = request == null ? null : request.scriptCode();
        InterviewScript script = scriptCode == null || scriptCode.isBlank()
                ? null
                : scriptRepository.findByCode(scriptCode).orElse(null);
        if (script == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "剧本不存在：" + scriptCode);
        }

        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + id));
        session.setScriptId(script.getId());
        session.setEventSequence(0);
        // 章节回到首章（或调用方指定的起始章节）：否则「演完一场 → stop → 再 start」会从上一场
        // 停下的章节续跑，不是 runFromStart 承诺的「从章节首事件开始」。
        // 起始章节来自规格 E5b §1.2：试玩「从本章开始 / 从某章试玩」要指定入口章节。
        String from = request == null ? null : request.fromChapter();
        if (from != null && !from.isBlank()) {
            // 仓库里没有 existsByScriptIdAndCode，用已有的 findByScriptIdAndCode 判空，不新造同名方法
            boolean exists = chapterRepository.findByScriptIdAndCode(script.getId(), from).isPresent();
            if (!exists) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "剧本没有这一章：" + from);
            }
            session.setCurrentChapter(from);
        } else {
            session.setCurrentChapter(script.getIntroChapter());
        }
        session.setVariableInfo(EMPTY_VARS);
        sessionRepository.save(session);
        log.info("[ScriptPlayController] 会话 {} 开演剧本 {}", id, script.getCode());

        return stream(id, sink -> engine.runFromStart(id, sink));
    }

    /**
     * 收演：解绑剧本、清空进度与变量，场次状态保持 {@code running}——回到自由对话。
     */
    @PostMapping("/{id}/script/stop")
    public Map<String, Boolean> stop(@PathVariable Long id) {
        engine.ensureExists(id);
        InterviewSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + id));
        session.setScriptId(null);
        session.setVariableInfo(EMPTY_VARS);
        session.setEventSequence(0);
        sessionRepository.save(session);
        log.info("[ScriptPlayController] 会话 {} 退出剧本，回到自由对话", id);
        return Map.of("ok", true);
    }

    /**
     * 剧本输入框提交（原版 {@code script_submit_input}）：是否接受由引擎按当前阻塞点判定。
     */
    @PostMapping(value = "/{id}/script/input", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter input(@PathVariable Long id, @RequestBody(required = false) SendMessageRequest request) {
        engine.ensureExists(id);
        String text = request == null || request.text() == null ? "" : request.text();
        return stream(id, sink -> engine.submitAndRun(id, text, sink));
    }

    /**
     * 选项点击（原版 {@code script_submit_choice}）：强制走 choice 分支，
     * 不受 {@code allow_free} 限制——点选项本来就该直接匹配。
     */
    @PostMapping(value = "/{id}/script/choice", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter choice(@PathVariable Long id, @RequestBody(required = false) ScriptChoiceRequest request) {
        engine.ensureExists(id);
        String choice = request == null || request.choice() == null ? "" : request.choice();
        return stream(id, sink -> engine.submitAndRun(id, choice, sink, true));
    }

    /** 引擎的一轮推进丢到工作线程，SSE 边跑边发；异常统一转成 {@code ai:error}。 */
    private SseEmitter stream(Long id, EngineCall call) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        SseEventSink sink = new SseEventSink(emitter);
        engineExecutor.submit(() -> {
            try {
                call.run(sink);
                emitter.complete();
            } catch (ScriptInputRejectedException e) {
                // 拒绝：原版返回 Err，这里下发 ai:error + status:reset，且不落任何台词
                log.info("[ScriptPlayController] 会话 {} 本次提交被拒绝：{}", id, e.getMessage());
                sink.error(e.getMessage());
                emitter.complete();
            } catch (Exception e) {
                log.warn("[ScriptPlayController] 会话 {} 剧本推进失败：{}", id, e.toString());
                sink.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 一次引擎调用（方法引用即可）。 */
    @FunctionalInterface
    private interface EngineCall {
        void run(SseEventSink sink);
    }
}

package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.EngineSignal;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.engine.ScriptEvent;

/**
 * 输入事件，对应 {@code script_engine/events/input_event.rs}。
 *
 * <p>原版在这里 {@code rx.await} 阻塞等前端回填，收到后把用户输入落成一条
 * {@code LineAttribute::User} 台词。Java 没有进程内阻塞上下文，按规格 §1 映射为：
 * 推 {@code input{hint}} → 返回 {@link EngineSignal#INPUT} → 主循环 return；
 * 用户提交走下一次请求（{@code InterviewEngine.submitAndRun} 里落用户台词再续跑）。
 *
 * <p>阻塞期间要在 {@code variable_info} 里留 {@code awaitingInput=true}，否则下一次
 * {@code /script/input} 无从判断「此刻有没有事件在等输入」，只能一律接受（原版是直接拒绝的）。
 */
public class InputEvent implements ScriptEvent {

    public static final String TYPE = "input";

    /** 原版缺省提示词。 */
    private static final String DEFAULT_HINT = "请输入...";

    @Override
    public String execute(EngineContext ctx) {
        // 原版是「字段缺失才用缺省值」：种子数据里 hint: "" 是显式的空提示，要保持空串
        String hint = ctx.event() != null && ctx.event().hasNonNull("hint")
                ? ctx.event().get("hint").asText()
                : DEFAULT_HINT;
        ctx.sink().input(hint);
        // 执行到位（=原版 rx.await 挂起）→ 标记等输入；引擎在本事件结束后统一落库
        if (ctx.vars() != null) {
            ctx.vars().put(InterviewEngine.AWAITING_INPUT_KEY, true);
        }
        return EngineSignal.INPUT;
    }
}

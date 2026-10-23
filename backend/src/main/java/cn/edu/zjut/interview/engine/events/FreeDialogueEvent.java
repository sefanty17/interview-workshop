package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.EngineSignal;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.engine.ScriptEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 自由对话事件，对应 {@code script_engine/events/free_dialogue_event.rs}。
 *
 * <p>原版是一个「等输入 → 生成回复 → 判断是否最后一轮」的进程内循环，收尾靠
 * {@code end_line} 子串命中或 {@code max_rounds} 用尽。Java 侧没有阻塞上下文，
 * 按规格 §1 把循环状态写进 {@code variable_info.freeDialogue}，跨请求续跑：
 *
 * <pre>{"switch": true, "maxRounds": n, "endLine": "...", "used": 0}</pre>
 *
 * <p>进入时推 {@code input(hint)} 让前端进输入态，返回 {@link EngineSignal#FREE_DIALOGUE} 结束本次推进。
 * 之后每次 {@code submitAndRun} 走 {@code InterviewEngine} 的自由对话分支，判定为最后一轮后
 * 把 {@code endLine} 当旁白落库并清掉 {@code freeDialogue}，继续主循环。
 *
 * <p>收尾条件照 {@code free_dialogue_event.rs:155} 与 {@code :199}：{@code max_rounds} 用尽，
 * <b>或</b>用户输入命中 {@code end_line}（子串匹配）。剧本没写 {@code max_rounds} 时原版存
 * {@code -1}（{@code :69}），{@code rounds >= max_rounds} 永不成立，此时只靠 {@code end_line} 收尾。
 */
public class FreeDialogueEvent implements ScriptEvent {

    public static final String TYPE = "free_dialogue";

    /** 变量表里的自由对话状态键。 */
    public static final String VAR_KEY = "freeDialogue";

    /** 原版缺省结束词（{@code free_dialogue_event.rs:73}）。 */
    private static final String DEFAULT_END_LINE = "结束";

    /** 原版缺省提示词（{@code free_dialogue_event.rs:64}）。 */
    private static final String DEFAULT_HINT = "自由对话...";

    @Override
    public String execute(EngineContext ctx) {
        String hint = textField(ctx, "hint", "hint", DEFAULT_HINT);
        ObjectNode vars = ctx.vars();
        if (vars != null) {
            ObjectNode state = vars.putObject(VAR_KEY);
            state.put("switch", true);
            state.put("maxRounds", intField(ctx, "max_rounds", "maxRounds", -1));
            state.put("endLine", textField(ctx, "end_line", "endLine", DEFAULT_END_LINE));
            state.put("used", 0);
        }
        // 原版每轮都 emit InputPayload{hint, duration}（:176-180）：下发的是剧本写的 hint，不是空串
        ctx.sink().input(hint);
        // 执行到位（=原版 rx.await 挂起）→ 标记等输入；引擎在本事件结束后统一落库
        if (vars != null) {
            vars.put(InterviewEngine.AWAITING_INPUT_KEY, true);
        }
        return EngineSignal.FREE_DIALOGUE;
    }

    /** 剧本字段用 snake_case，规格 §5 的变量名用 camelCase，两处都给。 */
    private static int intField(EngineContext ctx, String snake, String camel, int fallback) {
        JsonNode event = ctx.event();
        if (event == null) {
            return fallback;
        }
        JsonNode node = event.hasNonNull(snake) ? event.get(snake) : event.get(camel);
        return node == null || !node.canConvertToInt() ? fallback : node.asInt();
    }

    private static String textField(EngineContext ctx, String snake, String camel, String fallback) {
        JsonNode event = ctx.event();
        if (event == null) {
            return fallback;
        }
        JsonNode node = event.hasNonNull(snake) ? event.get(snake) : event.get(camel);
        return node == null || !node.isTextual() ? fallback : node.asText();
    }
}

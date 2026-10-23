package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.engine.ConditionEvaluator;
import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.ScriptFunctions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 变量赋值事件，对应 {@code script_engine/events/set_variable_event.rs}。
 *
 * <p>遍历 {@code options[]}：condition 为空或求值命中才处理；每个 option 的 {@code actions[]}
 * 里只认 {@code type == "set_var"}，按 {@code parse_variable_action + apply_variable_action}
 * 写进 {@code variable_info}（原版写 {@code script_status.vars}）。其他动作类型记 warn
 * ——原版注释专门说明：{@code choices} 的 {@code handle_actions} 还支持 {@code add_line}，
 * 作者很容易以为两处通用，静默丢弃会让剧本行为莫名其妙。
 *
 * <p>不发任何 SSE 事件，返回 {@code null} 继续下一个事件。
 */
public class SetVariableEvent implements ScriptEvent {

    public static final String TYPE = "set_variable";

    private static final Logger log = LoggerFactory.getLogger(SetVariableEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        JsonNode options = event == null ? null : event.get("options");
        if (options == null || !options.isArray()) {
            return null;
        }
        for (JsonNode option : options) {
            String condition = option.path("condition").asText("");
            if (!condition.isEmpty() && !ConditionEvaluator.evaluate(condition, ctx.vars())) {
                continue;
            }
            JsonNode actions = option.get("actions");
            if (actions == null || !actions.isArray()) {
                continue;
            }
            for (JsonNode action : actions) {
                String actionType = action.path("type").asText("");
                if ("set_var".equals(actionType)) {
                    applySetVar(ctx.vars(), action.path("content").asText(""));
                } else {
                    log.warn("[SetVariableEvent] 忽略不支持的动作类型 '{}'（此事件仅支持 set_var）", actionType);
                }
            }
        }
        return null;
    }

    /**
     * 执行一条 {@code set_var} 动作（{@code set_variable_event.rs:57-88}）。
     *
     * @return 是否写入成功；{@code content} 解析不了时记 warn 并返回 {@code false}
     */
    public static boolean applySetVar(ObjectNode vars, String content) {
        ScriptFunctions.VariableAction action = ScriptFunctions.parseVariableAction(content);
        if (action == null) {
            log.warn("[SetVariableEvent] 无法解析操作: '{}'", content);
            return false;
        }
        JsonNode current = vars.get(action.name());
        vars.set(action.name(), ScriptFunctions.applyVariableAction(action.op(), current, action.value()));
        log.info("[SetVariableEvent] {} -> {}", content, vars.get(action.name()));
        return true;
    }
}

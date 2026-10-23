package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.engine.ConditionEvaluator;
import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.EngineSignal;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.ScriptFunctions;
import cn.edu.zjut.interview.engine.payload.ChoiceItem;
import cn.edu.zjut.interview.engine.payload.ChoicePayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 选项事件，对应 {@code script_engine/events/choice_event.rs}。
 *
 * <p>原版流程：构建选项列表（无文案的兜底选项不给前端；条件不满足的标
 * {@code disabled + reason=lock_hint}）→ emit {@code script:choice} → 建 oneshot 通道并
 * {@code rx.await} 等用户选择 → 用 {@code process_options} 匹配选项并执行其动作 →
 * 一个都没命中时把原始输入落成 USER 台词。
 *
 * <p><b>Java 侧的挂起</b>（规格 B7 §任务一.1）：没有进程内 oneshot，改成把
 * 「原始 options + allowFree」写进 {@code variable_info.pendingChoice}，返回
 * {@link EngineSignal#CHOICE} 让主循环停在本轮；用户提交时由
 * {@code InterviewEngine#submitAndRun} 先查这个键，按同一套匹配规则续跑。
 *
 * <p>事件类型串沿用原版 {@code "choices"}（{@code choice_event.rs:152}，
 * 剧本 YAML 里也是 {@code type: choices}），类名按规格叫 {@code ChoiceEvent}。
 */
public class ChoiceEvent implements ScriptEvent {

    /** 事件类型串，与 {@code choice_event.rs:152 event_type()} 一致。 */
    public static final String TYPE = "choices";

    /** 变量表里记住「挂起中的选项」的键（规格 B7 指定的持久化等价物）。 */
    public static final String PENDING_KEY = "pendingChoice";

    /** {@code pendingChoice.options}：事件里的原始 options（含无文案的兜底项）。 */
    public static final String OPTIONS_KEY = "options";

    /** {@code pendingChoice.allowFree}：是否接受自由文本转投。 */
    public static final String ALLOW_FREE_KEY = "allowFree";

    private static final Logger log = LoggerFactory.getLogger(ChoiceEvent.class);

    @Override
    public String execute(EngineContext ctx) {
        JsonNode event = ctx.event();
        JsonNode options = optionsOf(ctx);
        boolean allowFree = event != null && event.path("allow_free").asBoolean(false);
        Double duration = ScriptEvent.parseDuration(event);

        // choice_event.rs:59-82 —— 条件不满足的选项标记 disabled；没文案的兜底项不发给前端
        ctx.sink().choice(new ChoicePayload(choices(options, ctx.vars()), allowFree, duration));

        // choice_event.rs:87-93 —— 原版把 sender 存进 channels，这里落库
        ObjectNode pending = ctx.objectMapper().createObjectNode();
        pending.set(OPTIONS_KEY, options.deepCopy());
        pending.put(ALLOW_FREE_KEY, allowFree);
        ctx.vars().set(PENDING_KEY, pending);
        log.info("[ChoiceEvent] 挂起等待选择，选项数 {}，allowFree={}", options.size(), allowFree);
        return EngineSignal.CHOICE;
    }

    /**
     * 给前端的选项列表（{@code choice_event.rs:59-82}）。
     *
     * <p>{@code text} 缺失或不是字符串的选项直接丢掉（原版 {@code filter_map} 的 {@code ?}）；
     * {@code text: ""} 算存在，照样下发。条件求值不过 → {@code disabled=true}，{@code reason}
     * 取作者的 {@code lock_hint}（没写则为 {@code null}，不序列化）。
     */
    public static List<ChoiceItem> choices(JsonNode options, JsonNode vars) {
        List<ChoiceItem> items = new ArrayList<>();
        if (options == null || !options.isArray()) {
            return items;
        }
        for (JsonNode option : options) {
            JsonNode textNode = option.get("text");
            if (textNode == null || !textNode.isTextual()) {
                continue;
            }
            String condition = option.path("condition").asText("");
            if (!condition.isEmpty() && !ConditionEvaluator.evaluate(condition, vars)) {
                JsonNode hint = option.get("lock_hint");
                items.add(new ChoiceItem(textNode.asText(), true,
                        hint != null && hint.isTextual() ? hint.asText() : null));
                continue;
            }
            items.add(new ChoiceItem(textNode.asText(), false, null));
        }
        return items;
    }

    /**
     * 在 options 里找用户选择命中的那一项，返回该 option 节点；没命中返回 {@code null}。
     *
     * <p>照搬 {@code script_function.rs:243 process_options}：先判 condition（不过就跳过，
     * 因此 disabled 项永不命中——这一点没有单独的分支），再看文本：选项写了 text 就必须
     * 与输入完全相等，没写 text 的兜底项匹配任何输入。
     */
    public static JsonNode match(JsonNode options, String input, JsonNode vars) {
        if (options == null || !options.isArray()) {
            return null;
        }
        String choice = input == null ? "" : input;
        for (JsonNode option : options) {
            String condition = option.path("condition").asText("");
            if (!condition.isEmpty() && !ConditionEvaluator.evaluate(condition, vars)) {
                continue;
            }
            String text = option.path("text").asText("");
            if (!text.isEmpty() && !text.equals(choice)) {
                continue;
            }
            return option;
        }
        return null;
    }

    /**
     * 执行选项动作，照搬 {@code script_function.rs:278 handle_actions}。
     *
     * <p>支持 {@code add_line}（落 USER 台词，先做占位符替换）与 {@code set_var}；
     * 其他类型记 warn（原版同样 warn）。
     */
    public static void handleActions(EngineContext ctx, JsonNode actions) {
        if (actions == null || !actions.isArray()) {
            return;
        }
        for (JsonNode action : actions) {
            String type = action.path("type").asText("");
            switch (type) {
                case "add_line" -> {
                    String content = ScriptFunctions.replacePlaceholder(
                            action.path("content").asText(""), ctx.playerName());
                    ctx.saveMessage(InterviewMessage.ROLE_USER, content, "text", null, null, null);
                }
                case "set_var" -> SetVariableEvent.applySetVar(ctx.vars(), action.path("content").asText(""));
                default -> log.warn("[ChoiceEvent] 未知的选项动作类型: '{}'", type);
            }
        }
    }

    private static JsonNode optionsOf(EngineContext ctx) {
        JsonNode event = ctx.event();
        if (event != null) {
            JsonNode options = event.get("options");
            if (options != null && options.isArray()) {
                return options;
            }
        }
        return ctx.objectMapper().createArrayNode();
    }
}

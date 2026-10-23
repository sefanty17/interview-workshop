package cn.edu.zjut.interview.engine.events;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.engine.ConditionEvaluator;
import cn.edu.zjut.interview.engine.EngineContext;
import cn.edu.zjut.interview.engine.ScriptEvent;
import cn.edu.zjut.interview.engine.ScriptFunctions;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 章节结束事件，对应 {@code script_engine/events/chapter_end_event.rs}。
 *
 * <p>三种子类型：
 * <ul>
 *   <li>{@code linear}：{@code next ?: next_chapter ?: "end"}</li>
 *   <li>{@code branching}：按变量求值选第一个命中的 option；一轮没命中再找 {@code default}</li>
 *   <li>{@code ai_judged}：必须调 LLM，LLM 不可用就抛异常终止剧本（不回落默认分支）</li>
 * </ul>
 */
public class ChapterEndEvent implements ScriptEvent {

    private static final Logger log = LoggerFactory.getLogger(ChapterEndEvent.class);

    public static final String TYPE = "chapter_end";

    /** ai_judged 没写 prompt 时的缺省判断语（原版同一句）。 */
    private static final String DEFAULT_JUDGE_PROMPT = "根据对话内容选择最合适的下一章节";

    /** 【对话记录】取最近多少条台词（原版 call_llm_for_judgment 用当前角色记忆，本项目按条数截断）。 */
    private static final int HISTORY_LIMIT = 20;

    @Override
    public String execute(EngineContext ctx) throws Exception {
        JsonNode event = ctx.event();
        String endType = event.path("end_type").asText("linear");

        String next = switch (endType) {
            case "linear" -> linear(event);
            case "branching" -> branching(event, ctx);
            case "ai_judged" -> callLlmForJudgment(event, ctx);
            default -> {
                log.warn("[ChapterEndEvent] 未知的 end_type: '{}'，默认 end", endType);
                yield "end";
            }
        };

        log.info("[ChapterEndEvent] end_type={} → next: '{}'", endType, next);
        return next;
    }

    private String linear(JsonNode event) {
        JsonNode next = event.hasNonNull("next") ? event.get("next") : event.get("next_chapter");
        return next == null || !next.isTextual() ? "end" : next.asText();
    }

    private String branching(JsonNode event, EngineContext ctx) {
        JsonNode options = event.path("options");
        String result = "end";
        for (JsonNode option : options) {
            String condition = option.path("condition").asText("");
            if (condition.isEmpty() || ConditionEvaluator.evaluate(condition, ctx.vars())) {
                JsonNode next = option.get("next");
                if (next != null && next.isTextual()) {
                    result = next.asText();
                    break;
                }
            }
        }
        if ("end".equals(result)) {
            for (JsonNode option : options) {
                if (option.path("default").asBoolean(false)) {
                    JsonNode next = option.get("next");
                    if (next != null && next.isTextual()) {
                        result = next.asText();
                    }
                }
            }
        }
        return result;
    }

    /**
     * 调 LLM 判断下一章，逐字照搬 {@code call_llm_for_judgment}。
     *
     * <p>LLM 不可用直接抛异常终止剧本——回落默认分支会让剧本按错误逻辑继续跑。
     */
    private String callLlmForJudgment(JsonNode event, EngineContext ctx) throws Exception {
        LlmClient llm = ctx.llmClient();
        if (llm == null) {
            throw new IllegalStateException(
                    "「AI 判断」章节结束需要大模型判断走向，但 LLM 不可用，剧本终止。请先配置并选择模型。");
        }

        JsonNode options = event.path("options");
        List<String> optionNames = new ArrayList<>();
        for (JsonNode option : options) {
            JsonNode name = option.get("name");
            if (name != null && name.isTextual()) {
                optionNames.add(name.asText());
            }
        }

        String conversation = conversationText(ctx);
        String promptText = event.hasNonNull("prompt") ? event.get("prompt").asText() : DEFAULT_JUDGE_PROMPT;

        StringBuilder optionsText = new StringBuilder();
        for (int i = 0; i < optionNames.size(); i++) {
            if (i > 0) {
                optionsText.append('\n');
            }
            optionsText.append(i + 1).append(". ").append(optionNames.get(i));
        }

        String fullPrompt = promptText
                + "\n\n【对话记录】:\n" + (conversation.isEmpty() ? "（无对话记录）" : conversation)
                + "\n\n【可选章节】:\n" + optionsText
                + "\n\n请只回复章节名称本身，不要包含其他内容。";

        log.info("[ChapterEndEvent] 请求 LLM 判断下一章节...");
        String response = llm.complete(List.of(LlmMessage.user(fullPrompt)));
        response = response == null ? "" : response.trim();
        log.info("[ChapterEndEvent] LLM 判断结果: '{}'", response);

        String matched = ScriptFunctions.matchAiResponseOptions(response, options);
        if (matched != null) {
            return matched;
        }
        if (!options.isEmpty()) {
            JsonNode firstNext = options.get(0).get("next");
            if (firstNext != null && firstNext.isTextual()) {
                return firstNext.asText();
            }
        }
        return "end";
    }

    /** 最近 20 条台词，{@code role: content}，跳过 system（同原版 memory 过滤）。 */
    private String conversationText(EngineContext ctx) {
        List<InterviewMessage> recent = new ArrayList<>(
                ctx.messageRepository().findTop20BySessionIdOrderBySeqDesc(ctx.sessionId()));
        Collections.reverse(recent);
        List<String> lines = new ArrayList<>();
        for (InterviewMessage message : recent) {
            if (InterviewMessage.ROLE_SYSTEM.equals(message.getRole())) {
                continue;
            }
            lines.add(message.getRole() + ": " + (message.getContent() == null ? "" : message.getContent()));
        }
        return String.join("\n", lines);
    }
}

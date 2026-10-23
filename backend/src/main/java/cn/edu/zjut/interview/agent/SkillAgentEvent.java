package cn.edu.zjut.interview.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

/**
 * Skill Agent 的流式事件，逐字对齐原版
 * {@code crates/ling-chat-main/src/ai_service/skill_agent/events.rs}。
 *
 * <p>原版是 {@code #[serde(tag = "type", rename_all = "snake_case")]} 的枚举：
 * 每个变体多一个 {@code "type"} 判别字段，其余字段是 snake_case，且<b>只有该变体自己的字段</b>。
 * Java 没有带标签联合，这里用一个「全部可选字段 + {@code @JsonInclude(NON_NULL)}」的
 * record 等价表达：序列化结果与原版逐字节一致（该变体用不到的字段直接不出现），
 * 判别字段由各静态工厂写入。
 *
 * <p>10 个变体（events.rs 里的全部）：status / message_delta / item_start /
 * reasoning / tool_call / tool_result / pending_approval / done /
 * conversation_title / error。最后两个规格 §3.3 没列，但 events.rs 里有，照搬
 * （events.rs 是事件协议的唯一真相源）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SkillAgentEvent(
        /** 判别字段：status / message_delta / item_start / reasoning / tool_call / tool_result / pending_approval / done / conversation_title / error。 */
        String type,
        /** status / message_delta / reasoning 的正文。 */
        String content,
        /** item_start：队列里的位置（从 1 起）。 */
        Integer index,
        /** item_start：本轮共几项。 */
        Integer total,
        /** item_start / conversation_title 的标题文本。 */
        String title,
        /** tool_call / tool_result 的调用 id。 */
        String callId,
        /** 工具名（tool_call / tool_result / pending_approval）。 */
        String tool,
        /** 归一化后的参数对象（tool_call / pending_approval）。 */
        Object args,
        /** LLM 返回的原始参数 JSON 字符串，可能被截断或非法。 */
        String rawArgs,
        /** tool_result：是否成功。 */
        Boolean ok,
        /** tool_result：工具输出。 */
        String output,
        /** tool_result：失败原因。 */
        String error,
        /** pending_approval：审批请求 id（{@code POST /api/editor/agent/approvals/{requestId}} 用的就是它）。 */
        String requestId,
        /** done：本轮完整正文。 */
        String finalText,
        /** done：本轮累计 token 用量；provider 未上报时为 {@code null}。 */
        Usage usage,
        /** error：错误信息（原版变体字段名是 message，不是 content）。 */
        String message) {

    /** 运行生命周期状态（如「思考中…」「已停止生成」）。 */
    public static SkillAgentEvent status(String content) {
        return new SkillAgentEvent("status", content, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** 流式文本增量。 */
    public static SkillAgentEvent messageDelta(String content) {
        return new SkillAgentEvent("message_delta", content, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /**
     * 一项的开始（一轮可能逐项做多件事）：带的是这一项的标题文本，语义是分段边界。
     *
     * @param index 队列里的位置（从 1 起）
     * @param total 本轮共几项
     * @param title 这一项的标题（前端当正文用）
     */
    public static SkillAgentEvent itemStart(int index, int total, String title) {
        return new SkillAgentEvent("item_start", null, index, total, title, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** 思考链增量（仅统计展示，不进入正式回复）。 */
    public static SkillAgentEvent reasoning(String content) {
        return new SkillAgentEvent("reasoning", content, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** 一个工具即将被调用。 */
    public static SkillAgentEvent toolCall(String callId, String tool, Map<String, Object> args, String rawArgs) {
        return new SkillAgentEvent("tool_call", null, null, null, null, callId, tool, args, rawArgs,
                null, null, null, null, null, null, null);
    }

    /** 工具执行结果。 */
    public static SkillAgentEvent toolResult(String callId, String tool, boolean ok, String output, String error) {
        return new SkillAgentEvent("tool_result", null, null, null, null, callId, tool, null, null,
                ok, output, error, null, null, null, null);
    }

    /** 命令需要用户审批。 */
    public static SkillAgentEvent pendingApproval(String requestId, String tool, Map<String, Object> args) {
        return new SkillAgentEvent("pending_approval", null, null, null, null, null, tool, args, null,
                null, null, null, requestId, null, null, null);
    }

    /** 本轮对话结束。 */
    public static SkillAgentEvent done(String finalText, Usage usage) {
        return new SkillAgentEvent("done", null, null, null, null, null, null, null, null,
                null, null, null, null, finalText, usage, null);
    }

    /** 会话标题已自动生成（首轮回复结束后由后台任务生成，经此事件通知前端刷新列表）。 */
    public static SkillAgentEvent conversationTitle(String title) {
        return new SkillAgentEvent("conversation_title", null, null, null, title, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** 致命错误。 */
    public static SkillAgentEvent error(String message) {
        return new SkillAgentEvent("error", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, message);
    }

    /**
     * Token 用量（原版 {@code events.rs} 的 {@code Usage}）。
     *
     * <p>{@code total_tokens} 原版是独立字段（不是前两项之和），照搬；
     * 字段名走 snake_case，输出即 prompt_tokens / completion_tokens / total_tokens / cached_tokens。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Usage(long promptTokens, long completionTokens, long totalTokens, long cachedTokens) {
    }
}

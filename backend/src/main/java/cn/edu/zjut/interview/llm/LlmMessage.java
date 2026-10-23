package cn.edu.zjut.interview.llm;

/**
 * 发给 OpenAI 兼容接口的一条消息，对应 LingChat 的 {@code types::LlmMessage}。
 *
 * <p>只保留本项目的两个字段：{@code role} / {@code content}；Rust 侧的
 * {@code tool_calls} / {@code tool_call_id} / {@code image_data_url} 本批不用。
 * 序列化成 JSON 时字段名就是 {@code role} / {@code content}。
 */
public record LlmMessage(String role, String content) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static LlmMessage system(String content) {
        return new LlmMessage(ROLE_SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(ROLE_USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(ROLE_ASSISTANT, content);
    }
}

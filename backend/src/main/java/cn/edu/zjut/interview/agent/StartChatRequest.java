package cn.edu.zjut.interview.agent;

/** {@code POST /api/editor/agent/conversations/{id}/chat} 的请求体（本批只用到 message）。 */
public record StartChatRequest(String message) {
}

package cn.edu.zjut.interview.agent;

/** {@code PUT /api/editor/agent/conversations/{id}/title} 的请求体。 */
public record RenameConversationRequest(String title) {
}

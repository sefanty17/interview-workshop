package cn.edu.zjut.interview.agent;

/**
 * {@code POST /api/editor/agent/conversations/{id}/rewind} 的请求体。
 *
 * @param messageId 回溯起点：删掉 id &gt;= 它的全部消息（通常是本轮用户消息的 id）
 */
public record RewindRequest(Long messageId) {
}

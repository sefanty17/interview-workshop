package cn.edu.zjut.interview.agent;

/**
 * {@code POST /api/editor/agent/resolve-script-key} 的请求体。
 *
 * <p>原版是 {@code editor_agent_resolve_script_key(conversation_id)}；端点路径被规格写成
 * 不带 id 的 {@code /resolve-script-key}，所以会话 id 走 body。
 */
public record ResolveScriptKeyRequest(Long conversationId) {
}

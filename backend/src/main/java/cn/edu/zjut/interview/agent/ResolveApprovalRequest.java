package cn.edu.zjut.interview.agent;

/**
 * {@code POST /api/editor/agent/approvals/{requestId}} 的请求体
 * （对齐原版 {@code editor_agent_resolve_approval(request_id, allowed)}）。
 */
public record ResolveApprovalRequest(boolean allowed) {
}

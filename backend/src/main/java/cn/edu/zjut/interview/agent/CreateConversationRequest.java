package cn.edu.zjut.interview.agent;

/**
 * {@code POST /api/editor/agent/conversations} 的请求体。
 *
 * <p>原版 {@code editor_agent_create_conversation} 只收 {@code scriptKey}（标题恒为 null，
 * 首轮回复后自动生成）；本项目按规格 E7a1 的验收脚本（{@code -d '{"title":"tmp-conv"}'}）
 * 多收一个可选 {@code title}。
 */
public record CreateConversationRequest(
        String title,
        String scriptKey) {
}

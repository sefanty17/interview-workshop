package cn.edu.zjut.interview.agent;

/**
 * {@code POST /api/editor/agent/resolve-script-key} 的响应。
 *
 * <p>原版直接返回 {@code Option<String>}（裸字符串或 null）；REST 里裸 null 会是空 body，
 * 前端不好区分「没绑到」和「请求失败」，所以包一层。
 */
public record AgentScriptKeyView(
        /** 会话归属的剧本 key；没绑到也没有可反推的证据时为 null。 */
        String scriptKey) {
}

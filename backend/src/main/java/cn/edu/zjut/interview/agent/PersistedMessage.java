package cn.edu.zjut.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 持久化消息（OpenAI 格式），对齐原版 {@code PersistedMessage}。
 *
 * <p>{@code toolCalls} 库里是 JSON 字符串，这里反序列化成 {@link JsonNode} 再出去
 * （原版同款 {@code serde_json::from_str}）；解析不出内容时给 null，不让一条坏数据炸掉整个列表。
 */
public record PersistedMessage(
        Long id,
        /** {@code user / assistant / tool / system}。 */
        String role,
        String content,
        /** assistant 的思考链（仅展示，不参与 LLM 上下文）。 */
        String reasoning,
        /** 产生该消息那一轮 LLM 调用的 token 用量；未上报为 null。 */
        Long promptTokens,
        Long completionTokens,
        /** 输入中命中缓存（cache read）的 token 数。 */
        Long cachedTokens,
        JsonNode toolCalls,
        String toolCallId,
        LocalDateTime createdAt) {
}

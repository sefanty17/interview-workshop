package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code script:player} 的 data，对应 {@code script_engine/responses.rs::PlayerPayload}
 * （{@code rename_all = "camelCase"}，{@code duration} 为 {@code Option} 且
 * {@code skip_serializing_if = "Option::is_none"}）。
 *
 * @param text        展示给玩家的文本
 * @param displayName 展示名（原版取 {@code displayName} 字段，缺省用玩家昵称）
 * @param duration    事件间隔（秒），没写就不序列化
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerPayload(String text, String displayName, Double duration) {
}

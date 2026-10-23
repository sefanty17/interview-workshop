package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code script:background} 的 data，对应 {@code script_engine/responses.rs::BackgroundPayload}。
 *
 * <p>{@code transition} 原版 {@code #[serde(default)]}（不是 Option），永远序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackgroundPayload(String imagePath, double transition, Double duration) {
}

package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code script:background-effect} 的 data，对应
 * {@code script_engine/responses.rs::BackgroundEffectPayload}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackgroundEffectPayload(String effect, Double duration) {
}

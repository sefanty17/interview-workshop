package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code script:present-pic} 的 data，对应 {@code script_engine/responses.rs::PresentPicPayload}。
 *
 * <p>{@code scale} 原版 {@code #[serde(default = "default_scale")]}（缺省 1.0，不是 Option），
 * 永远序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresentPicPayload(String imagePath, double scale, Double duration) {
}

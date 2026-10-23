package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code script:ambient} 的 data，对应 {@code script_engine/responses.rs::AmbientPayload}。
 *
 * <p>{@code volume / loop / stop / fade} 在原版都带 {@code #[serde(default)]}（不是 Option），
 * 永远序列化；其中 {@code is_loop} 显式 {@code rename = "loop"}，这里直接用记录分量名
 * {@code loop} 得到同一个键。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AmbientPayload(String ambientPath, double volume, boolean loop, boolean stop,
                             boolean fade, Double duration) {
}

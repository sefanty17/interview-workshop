package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code script:choice} 的 data，对应 {@code script_engine/responses.rs::ChoicePayload}。
 *
 * <p>{@code allow_free} 在原版显式 {@code #[serde(rename = "allowFree")]}，这里靠记录分量名
 * {@code allowFree} 得到同一个键；{@code duration} 可空且不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChoicePayload(List<ChoiceItem> choices, boolean allowFree, Double duration) {
}

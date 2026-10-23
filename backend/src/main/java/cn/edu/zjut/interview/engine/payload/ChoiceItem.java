package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个选项，对应 {@code script_engine/responses.rs::ChoiceItem}。
 *
 * @param text     选项文案
 * @param disabled 条件不满足时为 true（前端灰显并禁止点击）
 * @param reason   作者写的 {@code lock_hint}，没有就不序列化
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChoiceItem(String text, boolean disabled, String reason) {
}

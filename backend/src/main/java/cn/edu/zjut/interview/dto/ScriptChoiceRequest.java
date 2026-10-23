package cn.edu.zjut.interview.dto;

/**
 * {@code POST /api/sessions/{id}/script/choice} 的请求体（规格 F-1 §B3）。
 *
 * @param choice 用户点中的选项文案（原版 {@code script_submit_choice} 按选项文本匹配）
 */
public record ScriptChoiceRequest(String choice) {
}

package cn.edu.zjut.interview.dto;

/**
 * {@code POST /api/sessions/{id}/messages} 的请求体（规格 B3-BE §1.2）。
 *
 * @param text 候选人这一轮说的话（原始输入，旁白/临时段由后端清洗）
 */
public record SendMessageRequest(String text) {
}

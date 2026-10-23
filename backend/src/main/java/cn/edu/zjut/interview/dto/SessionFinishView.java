package cn.edu.zjut.interview.dto;

/**
 * {@code POST /api/sessions/{id}/finish} 的返回体（规格 B5 §6.1）：{@code {id, status}}；
 * 自 S5 起追加结束时的总分 {@code score}。
 */
public record SessionFinishView(Long id, String status, Integer score) {
}

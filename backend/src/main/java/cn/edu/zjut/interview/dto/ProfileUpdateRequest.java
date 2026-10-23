package cn.edu.zjut.interview.dto;

/**
 * {@code PUT /api/profile} 请求体（规格 B6 §1.1）：与 {@link ProfileView} 同字段，{@code id} 忽略。
 */
public record ProfileUpdateRequest(
        String nickname,
        String signature,
        String jobTarget,
        String school,
        String major,
        String grade,
        String studentNo,
        String resumeSummary) {
}

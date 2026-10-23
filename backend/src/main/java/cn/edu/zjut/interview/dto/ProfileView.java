package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.UserProfile;

/**
 * {@code GET/PUT /api/profile} 返回体（规格 B6 §1.1）。
 *
 * <p>照 {@link UserProfile} 的业务字段，{@code id / createdAt / updatedAt} 这类服务端生成字段不外吐。
 */
public record ProfileView(
        String nickname,
        String signature,
        String jobTarget,
        String school,
        String major,
        String grade,
        String studentNo,
        String resumeSummary) {

    /** 库里没有记录时的兜底身份（对齐 LingChat 默认玩家身份）。 */
    public static ProfileView fallback() {
        return new ProfileView("候选人", null, null, null, null, null, null, null);
    }

    public static ProfileView from(UserProfile e) {
        return new ProfileView(
                e.getNickname(),
                e.getSignature(),
                e.getJobTarget(),
                e.getSchool(),
                e.getMajor(),
                e.getGrade(),
                e.getStudentNo(),
                e.getResumeSummary());
    }
}

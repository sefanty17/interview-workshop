package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.UserProfile;
import cn.edu.zjut.interview.dto.ProfileUpdateRequest;
import cn.edu.zjut.interview.dto.ProfileView;
import cn.edu.zjut.interview.repository.UserProfileRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 用户身份接口（规格 B6 §1.3）。
 *
 * <p>{@code user_profile} 是单行表：GET 没有记录就返回兜底身份（昵称「候选人」），且不写库；
 * PUT 有则更新、无则新建，请求体里的 {@code id} 忽略。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    /** 兜底昵称，与 {@link ProfileView#fallback()} 保持一致。 */
    private static final String DEFAULT_NICKNAME = "候选人";

    private final UserProfileRepository profileRepository;

    public ProfileController(UserProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @GetMapping
    public ProfileView get() {
        return profileRepository.findFirstByOrderByIdAsc()
                .map(ProfileView::from)
                .orElseGet(ProfileView::fallback);
    }

    @PutMapping
    public ProfileView update(@RequestBody ProfileUpdateRequest request) {
        UserProfile profile = profileRepository.findFirstByOrderByIdAsc().orElseGet(UserProfile::new);
        LocalDateTime now = LocalDateTime.now();

        profile.setNickname(resolveNickname(request.nickname()));
        profile.setSignature(request.signature());
        profile.setJobTarget(request.jobTarget());
        profile.setSchool(request.school());
        profile.setMajor(request.major());
        profile.setGrade(request.grade());
        profile.setStudentNo(request.studentNo());
        profile.setResumeSummary(request.resumeSummary());
        if (profile.getId() == null) {
            profile.setCreatedAt(now);
        }
        profile.setUpdatedAt(now);

        return ProfileView.from(profileRepository.save(profile));
    }

    /** nickname 是 NOT NULL 列，空值回落成兜底昵称，避免 500。 */
    private static String resolveNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return DEFAULT_NICKNAME;
        }
        return nickname;
    }
}

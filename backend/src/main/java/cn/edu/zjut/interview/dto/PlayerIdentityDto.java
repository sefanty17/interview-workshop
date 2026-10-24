package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.PlayerIdentity;

import java.util.Map;

/**
 * 「我的身份」DTO（对齐 {@code player_identity/mod.rs:51 PlayerIdentitySummary}
 * 与 {@code api/player_identity.rs} 的返回形态）。
 *
 * <p>字段名用 camelCase，前端直接消费。原版的 {@code has_avatar} / {@code relation_count} /
 * {@code is_current} 分别落成 {@code hasAvatar} / {@code relationCount} / {@code isCurrent}。
 */
public final class PlayerIdentityDto {

    private PlayerIdentityDto() {
    }

    /** 详情：整张身份卡（{@code relations} 是 Map，不是 JSON 字符串）。 */
    public record PlayerIdentityView(
            String identityId,
            String name,
            String subtitle,
            String prompt,
            String avatar,
            Map<String, String> relations) {
    }

    /**
     * 列表项。
     *
     * <p>{@code name} 存的是 {@code displayName()}（已兜底「用户」）；
     * {@code relationCount} 只数<b>值非空</b>的关系；
     * {@code hasAvatar} 只表示有没有头像，不吐头像内容。
     */
    public record PlayerIdentitySummary(
            String identityId,
            String name,
            String subtitle,
            String prompt,
            boolean hasAvatar,
            int relationCount,
            boolean isCurrent) {
    }

    /** 空库时展示的默认身份（{@code id="me"}，不落库）。 */
    public static PlayerIdentityView defaultView() {
        return new PlayerIdentityView("me", "用户", "", "", null, Map.of());
    }
}

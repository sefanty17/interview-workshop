package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.PlayerIdentity;
import cn.edu.zjut.interview.repository.PlayerIdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 「我的身份」（对齐 {@code game_system/player_identity/mod.rs} 的读写部分）。
 *
 * <p>本批只落「当前身份是谁」这一件事，因为它是身份解耦的地基：
 * 玩家台词落库时要能写下"这是哪个身份说的、叫什么名字"，
 * 否则多人场景下历史里分不清谁在说话。
 *
 * <p>关系解析与两块注入（{@code build_player_block} / {@code build_peers_block}）在
 * {@link PlayerIdentityPrompt}。
 */
@Service
public class PlayerIdentityService {

    /** 没建过任何身份时用的兜底身份 id。 */
    public static final String DEFAULT_IDENTITY_ID = "me";

    private final PlayerIdentityRepository repository;

    public PlayerIdentityService(PlayerIdentityRepository repository) {
        this.repository = repository;
    }

    /** 当前生效的身份；没设过返回 {@link Optional#empty()}（调用方自行决定要不要兜底）。 */
    public Optional<PlayerIdentity> current() {
        return repository.findFirstByIsCurrentTrue();
    }

    /**
     * 当前身份的显示名；没有身份时回落「用户」。
     *
     * <p>玩家台词落 {@code display_name} 走这里，保证每条玩家台词都带得出一个名字。
     */
    public String currentDisplayName() {
        return current().map(PlayerIdentity::displayName).orElse(PlayerIdentity.DEFAULT_NAME);
    }

    /** 当前身份 id；没有身份时返回 {@code null}（老库、没建过身份的历史数据）。 */
    public String currentIdentityId() {
        return current().map(PlayerIdentity::getIdentityId).orElse(null);
    }

    /** 列出全部身份（切换用，按 id 升序）。 */
    public java.util.List<PlayerIdentity> list() {
        return repository.findAll().stream()
                .sorted(java.util.Comparator.comparing(PlayerIdentity::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /** 切换当前身份：先把别的都置否，再置这一个（同一时刻只有一个生效）。 */
    @Transactional
    public Optional<PlayerIdentity> switchTo(String identityId) {
        Optional<PlayerIdentity> target = repository.findByIdentityId(identityId);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        for (PlayerIdentity each : repository.findAll()) {
            boolean shouldBeCurrent = each.getId().equals(target.get().getId());
            if (!Boolean.valueOf(shouldBeCurrent).equals(each.getIsCurrent())) {
                each.setIsCurrent(shouldBeCurrent);
                each.setUpdatedAt(LocalDateTime.now());
                repository.save(each);
            }
        }
        return target;
    }
}

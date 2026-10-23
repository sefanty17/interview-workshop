package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.PlayerIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** {@code player_identity}：玩家身份卡（对齐原版身份索引表）。 */
public interface PlayerIdentityRepository extends JpaRepository<PlayerIdentity, Long> {

    /** 按身份 id 查（关系键 {@code me:<身份 id>} 用的是这个）。 */
    Optional<PlayerIdentity> findByIdentityId(String identityId);

    /** 当前生效的那个身份；没设过就是空。 */
    Optional<PlayerIdentity> findFirstByIsCurrentTrue();
}

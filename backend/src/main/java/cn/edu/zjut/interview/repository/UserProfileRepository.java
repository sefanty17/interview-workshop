package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /** user_profile 是单行表，取第一条即可。 */
    Optional<UserProfile> findFirstByOrderByIdAsc();
}

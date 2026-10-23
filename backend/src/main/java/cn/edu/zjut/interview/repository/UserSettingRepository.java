package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.UserSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code user_settings}：主键就是配置键本身。 */
public interface UserSettingRepository extends JpaRepository<UserSetting, String> {
}

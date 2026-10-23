package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 设置项覆盖值，对应 DDL {@code user_settings}。
 *
 * <p>照原版 {@code settings.json} 的形状做成<b>扁平 key-value</b>（一行一个 key），
 * 不是「一个大 JSON 字段」——原版 {@code save_settings} 收到的就是
 * {@code BTreeMap<String,String>}，逐 key 写 store。
 *
 * <p>表里只存「用户改过的项」：没有行 = 用注册表里的默认值，所以默认值只有一份真相源
 * （见 {@link cn.edu.zjut.interview.config.ConfigService}）。
 */
@Entity
@Table(name = "user_settings")
public class UserSetting {

    /** 配置键，同时是主键（{@code llm.temperature} 这种点号命名）。 */
    @Id
    @Column(name = "config_key", length = 64, nullable = false)
    private String configKey;

    @Column(name = "config_value", length = 2048, nullable = false)
    private String configValue;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    public UserSetting() {
    }

    public UserSetting(String configKey, String configValue, LocalDateTime updatedAt) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.updatedAt = updatedAt;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

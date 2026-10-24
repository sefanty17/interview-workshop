package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * 玩家身份卡，对应 DDL {@code player_identity}（对齐 LingChat {@code db/entities/player_identity.rs}
 * 与 {@code game_system/player_identity/mod.rs::PlayerIdentity}）。
 *
 * <p><b>为什么要有这张表</b>：玩家身份与角色身份必须先解耦，多人对话才做得下去。
 * 一旦场次里出现"我"和多个面试官，历史里同时存在多个身份的台词；
 * 只靠 {@code role=user} 分不清谁说的，模型会把邻座面试官的话当成玩家接着说的话。
 *
 * <p><b>与原版的差异</b>：原版是「文件是真源（身份卡目录），本表只做索引」；
 * 本项目没有身份卡目录，表即真源（技术栈差异，不是功能裁剪）。
 */
@Entity
@Table(name = "player_identity")
public class PlayerIdentity {

    /** 名字留空时的兜底称呼（对齐 {@code PlayerIdentity::display_name}）。 */
    public static final String DEFAULT_NAME = "用户";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 不可变 id；关系键与存档记录都用它（对齐 {@code PlayerIdentity.id}）。 */
    @Column(name = "identity_id", length = 64, nullable = false, unique = true)
    private String identityId;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "subtitle", length = 128)
    private String subtitle;

    /** 对所有角色公开的基础设定。 */
    @Column(name = "prompt", columnDefinition = "CLOB")
    private String prompt;

    @Column(name = "avatar", length = 255)
    private String avatar;

    /** 我对各个对象的出厂关系，JSON 对象：{@code {"ai:<角色code>": "...", "me:<身份id>": "..."}}。 */
    @Column(name = "relations", columnDefinition = "CLOB")
    private String relations;

    /**
     * 保存时的入参形态：编辑器传的是 Map，{@link cn.edu.zjut.interview.service.PlayerIdentityService#save}
     * 会把它序列化成 {@link #relations} 再落库。<b>不映射到列</b>。
     */
    @Transient
    private java.util.Map<String, String> relationMap;

    /** 当前生效的身份（同一时刻只有一个）。 */
    @Column(name = "is_current")
    private Boolean isCurrent = Boolean.FALSE;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    /** 显示名：名字留空时回落「用户」（对齐原版 {@code display_name()}）。 */
    public String displayName() {
        return name == null || name.trim().isEmpty() ? DEFAULT_NAME : name.trim();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdentityId() {
        return identityId;
    }

    public void setIdentityId(String identityId) {
        this.identityId = identityId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getRelations() {
        return relations;
    }

    public void setRelations(String relations) {
        this.relations = relations;
    }

    public java.util.Map<String, String> getRelationMap() {
        return relationMap;
    }

    public void setRelationMap(java.util.Map<String, String> relationMap) {
        this.relationMap = relationMap;
    }

    public Boolean getIsCurrent() {
        return isCurrent;
    }

    public void setIsCurrent(Boolean isCurrent) {
        this.isCurrent = isCurrent;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 角色卡，对应 DDL {@code interviewer}（对齐 LingChat 的 role）。
 *
 * <p>字段与 data/schema/schema-h2.sql 逐列对齐；CLOB 列在 Java 侧统一用 String，
 * 需要结构化时再反序列化。DDL 里有 DEFAULT 的列，这里给出同值初始值，
 * 避免后续 insert 时把 null 写进去盖掉库默认值。
 */
@Entity
@Table(name = "interviewer")
public class Interviewer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", length = 32, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "subtitle", length = 64)
    private String subtitle;

    @Column(name = "card_title", length = 128)
    private String cardTitle;

    @Column(name = "card_info", columnDefinition = "CLOB")
    private String cardInfo;

    @Column(name = "persona_prompt", columnDefinition = "CLOB", nullable = false)
    private String personaPrompt;

    @Column(name = "example_dialogue", columnDefinition = "CLOB")
    private String exampleDialogue;

    /**
     * 世界书（对齐角色卡目录里的 {@code worldbook.yml}）：整份 YAML 文本原样存库。
     * 空 = 不注入，行为与没有世界书时完全一致。
     */
    @Column(name = "worldbook", columnDefinition = "CLOB")
    private String worldbook;

    @Column(name = "thinking_message", length = 128)
    private String thinkingMessage;

    @Column(name = "avatar_mode", length = 16)
    private String avatarMode = "image";

    @Column(name = "scale")
    private Double scale = 1.0;

    @Column(name = "offset_x")
    private Double offsetX = 0.0;

    @Column(name = "offset_y")
    private Double offsetY = 0.0;

    @Column(name = "bubble_top")
    private Integer bubbleTop = 5;

    @Column(name = "bubble_left")
    private Integer bubbleLeft = 25;

    @Column(name = "user_alias", length = 64)
    private String userAlias;

    @Column(name = "user_alias_subtitle", length = 64)
    private String userAliasSubtitle;

    @Column(name = "display_role", length = 64)
    private String displayRole;

    @Column(name = "opening", length = 512)
    private String opening;

    @Column(name = "follow_up")
    private Integer followUp = 2;

    @Column(name = "speed")
    private Integer speed = 1;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "enabled")
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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

    public String getCardTitle() {
        return cardTitle;
    }

    public void setCardTitle(String cardTitle) {
        this.cardTitle = cardTitle;
    }

    public String getCardInfo() {
        return cardInfo;
    }

    public void setCardInfo(String cardInfo) {
        this.cardInfo = cardInfo;
    }

    public String getPersonaPrompt() {
        return personaPrompt;
    }

    public void setPersonaPrompt(String personaPrompt) {
        this.personaPrompt = personaPrompt;
    }

    public String getExampleDialogue() {
        return exampleDialogue;
    }

    public void setExampleDialogue(String exampleDialogue) {
        this.exampleDialogue = exampleDialogue;
    }

    public String getWorldbook() {
        return worldbook;
    }

    public void setWorldbook(String worldbook) {
        this.worldbook = worldbook;
    }

    public String getThinkingMessage() {
        return thinkingMessage;
    }

    public void setThinkingMessage(String thinkingMessage) {
        this.thinkingMessage = thinkingMessage;
    }

    public String getAvatarMode() {
        return avatarMode;
    }

    public void setAvatarMode(String avatarMode) {
        this.avatarMode = avatarMode;
    }

    public Double getScale() {
        return scale;
    }

    public void setScale(Double scale) {
        this.scale = scale;
    }

    public Double getOffsetX() {
        return offsetX;
    }

    public void setOffsetX(Double offsetX) {
        this.offsetX = offsetX;
    }

    public Double getOffsetY() {
        return offsetY;
    }

    public void setOffsetY(Double offsetY) {
        this.offsetY = offsetY;
    }

    public Integer getBubbleTop() {
        return bubbleTop;
    }

    public void setBubbleTop(Integer bubbleTop) {
        this.bubbleTop = bubbleTop;
    }

    public Integer getBubbleLeft() {
        return bubbleLeft;
    }

    public void setBubbleLeft(Integer bubbleLeft) {
        this.bubbleLeft = bubbleLeft;
    }

    public String getUserAlias() {
        return userAlias;
    }

    public void setUserAlias(String userAlias) {
        this.userAlias = userAlias;
    }

    public String getUserAliasSubtitle() {
        return userAliasSubtitle;
    }

    public void setUserAliasSubtitle(String userAliasSubtitle) {
        this.userAliasSubtitle = userAliasSubtitle;
    }

    public String getDisplayRole() {
        return displayRole;
    }

    public void setDisplayRole(String displayRole) {
        this.displayRole = displayRole;
    }

    public String getOpening() {
        return opening;
    }

    public void setOpening(String opening) {
        this.opening = opening;
    }

    public Integer getFollowUp() {
        return followUp;
    }

    public void setFollowUp(Integer followUp) {
        this.followUp = followUp;
    }

    public Integer getSpeed() {
        return speed;
    }

    public void setSpeed(Integer speed) {
        this.speed = speed;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

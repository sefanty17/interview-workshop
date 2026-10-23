package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 剧本编辑器 AI 助手（Skill Agent）的会话，对应 DDL {@code skill_agent_conversation}
 * （对齐原版 {@code db/entities/skill_agent_conversation.rs}）。
 *
 * <p>只记录创建时绑定的剧本 key，不存剧本内容快照（原版同语义）。
 */
@Entity
@Table(name = "skill_agent_conversation")
public class AgentConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", length = 128)
    private String title;

    /** 会话创建时打开的剧本 key，可空（老会话靠历史写入路径反推）。 */
    @Column(name = "script_key", length = 64)
    private String scriptKey;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getScriptKey() {
        return scriptKey;
    }

    public void setScriptKey(String scriptKey) {
        this.scriptKey = scriptKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

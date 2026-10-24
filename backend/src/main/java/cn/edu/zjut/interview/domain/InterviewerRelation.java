package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 角色对各方（玩家身份 / 其他角色）的出厂初始关系，对应 DDL {@code interviewer_relation}
 * （对齐 LingChat 的 {@code characters/<folder>/relations.yml}）。
 *
 * <p>键 {@code targetKey} 只有两种形态：{@code me:<身份id>}（对某个玩家身份）与
 * {@code ai:<角色code>}（对另一个角色）。游戏中的关系变化记在本局存档里，
 * 不写回这张表 —— 它只装「出厂设定」。
 */
@Entity
@Table(name = "interviewer_relation")
public class InterviewerRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "interviewer_code", length = 64, nullable = false)
    private String interviewerCode;

    @Column(name = "target_key", length = 128, nullable = false)
    private String targetKey;

    @Column(name = "relation_text", columnDefinition = "CLOB", nullable = false)
    private String relationText;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInterviewerCode() {
        return interviewerCode;
    }

    public void setInterviewerCode(String interviewerCode) {
        this.interviewerCode = interviewerCode;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public void setTargetKey(String targetKey) {
        this.targetKey = targetKey;
    }

    public String getRelationText() {
        return relationText;
    }

    public void setRelationText(String relationText) {
        this.relationText = relationText;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

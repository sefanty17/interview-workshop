package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 台词，对应 DDL {@code interview_message}（对齐 LingChat 的 line）。
 *
 * <p>role 值域 {@code system/user/assistant}；seq 是场次内递增序号，
 * MemoryBuilder 按 seq 升序还原对话。
 */
@Entity
@Table(name = "interview_message")
public class InterviewMessage {

    /** role 的合法取值。 */
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /** 玩家在 {@code sender_role_id} 里的固定取值（对齐 {@code line.rs}：0 = 玩家）。 */
    public static final int PLAYER_ROLE_ID = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "role", length = 16, nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "CLOB", nullable = false)
    private String content;

    @Column(name = "original_tag", length = 64)
    private String originalTag;

    @Column(name = "predicted_emotion", length = 32)
    private String predictedEmotion;

    @Column(name = "action", columnDefinition = "CLOB")
    private String action;

    @Column(name = "msg_type", length = 16)
    private String msgType = "text";

    @Column(name = "stage_code", length = 64)
    private String stageCode;

    /**
     * 说话的角色 id，对齐 LingChat {@code line.sender_role_id}：
     * {@code 0} = 玩家、{@code N} = 角色 id、{@code null} = 旁白 / system。
     */
    @Column(name = "sender_role_id")
    private Integer senderRoleId;

    @Column(name = "tokens")
    private Integer tokens;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOriginalTag() {
        return originalTag;
    }

    public void setOriginalTag(String originalTag) {
        this.originalTag = originalTag;
    }

    public String getPredictedEmotion() {
        return predictedEmotion;
    }

    public void setPredictedEmotion(String predictedEmotion) {
        this.predictedEmotion = predictedEmotion;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getStageCode() {
        return stageCode;
    }

    public void setStageCode(String stageCode) {
        this.stageCode = stageCode;
    }

    public Integer getSenderRoleId() {
        return senderRoleId;
    }

    public void setSenderRoleId(Integer senderRoleId) {
        this.senderRoleId = senderRoleId;
    }

    public Integer getTokens() {
        return tokens;
    }

    public void setTokens(Integer tokens) {
        this.tokens = tokens;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

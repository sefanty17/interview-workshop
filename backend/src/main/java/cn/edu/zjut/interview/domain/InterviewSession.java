package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 场次 + 运行状态，对应 DDL {@code interview_session}（对齐 LingChat 的 save + running_script）。
 */
@Entity
@Table(name = "interview_session")
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "interviewer_id", nullable = false)
    private Long interviewerId;

    @Column(name = "script_id")
    private Long scriptId;

    @Column(name = "job_code", length = 32, nullable = false)
    private String jobCode;

    @Column(name = "kps", length = 512)
    private String kps;

    /** 值域见 DDL 默认值：running。 */
    @Column(name = "status", length = 16)
    private String status = "running";

    @Column(name = "score")
    private Integer score;

    @Column(name = "current_chapter", length = 255)
    private String currentChapter;

    @Column(name = "event_sequence")
    private Integer eventSequence = 0;

    @Column(name = "variable_info", columnDefinition = "CLOB")
    private String variableInfo;

    @Column(name = "started_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime startedAt;

    @Column(name = "ended_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime endedAt;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    /** 试玩场次（剧本编辑器 PreviewStage）：不计分、不出现在场次列表，退出即删。 */
    @Column(name = "preview")
    private Boolean preview = Boolean.FALSE;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getInterviewerId() {
        return interviewerId;
    }

    public void setInterviewerId(Long interviewerId) {
        this.interviewerId = interviewerId;
    }

    public Long getScriptId() {
        return scriptId;
    }

    public void setScriptId(Long scriptId) {
        this.scriptId = scriptId;
    }

    public String getJobCode() {
        return jobCode;
    }

    public void setJobCode(String jobCode) {
        this.jobCode = jobCode;
    }

    public String getKps() {
        return kps;
    }

    public void setKps(String kps) {
        this.kps = kps;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getCurrentChapter() {
        return currentChapter;
    }

    public void setCurrentChapter(String currentChapter) {
        this.currentChapter = currentChapter;
    }

    public Integer getEventSequence() {
        return eventSequence;
    }

    public void setEventSequence(Integer eventSequence) {
        this.eventSequence = eventSequence;
    }

    public String getVariableInfo() {
        return variableInfo;
    }

    public void setVariableInfo(String variableInfo) {
        this.variableInfo = variableInfo;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean isPreview() {
        return preview;
    }

    public void setPreview(Boolean preview) {
        this.preview = preview;
    }
}

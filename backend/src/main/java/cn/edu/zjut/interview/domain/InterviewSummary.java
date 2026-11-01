package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 场次级对话总结，对应 DDL {@code interview_summary}（<b>本项目扩展（issue #4）</b>）。
 *
 * <p>与 {@link InterviewEvaluation} 的区别：评估是「打分 + 评价」，走面试官口径；
 * 这张表是「中立的事实摘要」，由独立的总结 agent 生成，不复用面试官人设，
 * 只记录问了什么、答了什么、卡在哪、用词如何，不给分数也不下结论。
 *
 * <p>{@code content} 存整份摘要 JSON（overview / questions / coverage / vocabulary），
 * 与 {@code interview_session.kps} 同一做法：本项目没有数组列，结构一律 JSON 存 CLOB。
 * 与评估表拆成两表而不是加列，是因为两者由不同链路、不同模型调用分别产出，
 * 失败互不影响——评估失败时摘要照样能用，反之亦然。
 *
 * <p>失败也要落一行（{@code status = failed} + {@code error}）：报告页据此显示
 * 「摘要生成失败」，而不是把「没生成」和「生成坏了」混成一个 404。
 */
@Entity
@Table(name = "interview_summary")
public class InterviewSummary {

    /** 生成成功。 */
    public static final String STATUS_OK = "ok";
    /** 生成失败（大模型调用失败 / 输出不合法 / 对话太短）。 */
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "status", length = 16)
    private String status = STATUS_OK;

    /** 摘要 JSON；失败时为 null。 */
    @Column(name = "content", columnDefinition = "CLOB")
    private String content;

    /** 失败原因（给报告页直接展示的短文案）；成功时为 null。 */
    @Column(name = "error", columnDefinition = "CLOB")
    private String error;

    /** 出这份摘要的模型名：换模型后旧摘要不可比，得能看出来是谁写的。 */
    @Column(name = "model", length = 64)
    private String model;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

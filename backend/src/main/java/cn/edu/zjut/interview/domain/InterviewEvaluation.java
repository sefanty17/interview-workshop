package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 场次级评估结论，对应 DDL {@code interview_evaluation}（<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>LingChat 没有评估功能，这张表与 {@link InterviewEvaluationDimension} 都是本项目新增。
 * 分成两表而不是一个 JSON 字段：成长曲线要按 {@code dim_code} 查趋势，
 * 存整块 JSON 就得每次全表扫出来在内存里拆。
 *
 * <p>{@code strengths / weaknesses / suggestions} 存的是 JSON 数组字符串
 * （与 {@code interview_session.kps} 同一做法：本项目没有数组列，数组一律 JSON 存 CLOB，
 * 读出来用 {@code ObjectMapper} 还原）。
 *
 * <p>一场只有一份：靠 {@code uk_evaluation_session(session_id)} 唯一约束 + 服务层「先删后插」
 * 实现重跑覆盖，而不是靠应用层查重。
 */
@Entity
@Table(name = "interview_evaluation")
public class InterviewEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "job_code", length = 32)
    private String jobCode;

    /** 加权总分 0..100，由四个维度分按权重算出（见 {@code EvaluationDimension}）。 */
    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(name = "summary", columnDefinition = "CLOB")
    private String summary;

    @Column(name = "strengths", columnDefinition = "CLOB")
    private String strengths;

    @Column(name = "weaknesses", columnDefinition = "CLOB")
    private String weaknesses;

    @Column(name = "suggestions", columnDefinition = "CLOB")
    private String suggestions;

    /** 出这份评估的模型名：换模型后旧评估不可比，得能看出来是谁打的。 */
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

    public String getJobCode() {
        return jobCode;
    }

    public void setJobCode(String jobCode) {
        this.jobCode = jobCode;
    }

    public Integer getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getStrengths() {
        return strengths;
    }

    public void setStrengths(String strengths) {
        this.strengths = strengths;
    }

    public String getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(String weaknesses) {
        this.weaknesses = weaknesses;
    }

    public String getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(String suggestions) {
        this.suggestions = suggestions;
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

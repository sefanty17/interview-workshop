package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 单个维度的分与评语，对应 DDL {@code interview_evaluation_dimension}
 * （<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>{@code dimCode} 取值来自 {@link cn.edu.zjut.interview.evaluation.EvaluationDimension}：
 * {@code technical / depth / logic / fit}，一场每维度恰好一行（唯一约束 {@code uk_evaluation_dim}）。
 */
@Entity
@Table(name = "interview_evaluation_dimension")
public class InterviewEvaluationDimension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "dim_code", length = 32, nullable = false)
    private String dimCode;

    @Column(name = "score", nullable = false)
    private Integer score;

    @Column(name = "comment", columnDefinition = "CLOB")
    private String comment;

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

    public String getDimCode() {
        return dimCode;
    }

    public void setDimCode(String dimCode) {
        this.dimCode = dimCode;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}

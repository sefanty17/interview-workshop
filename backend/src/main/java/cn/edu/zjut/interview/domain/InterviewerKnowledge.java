package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 角色卡「岗位知识」一栏的勾选结果，对应 DDL {@code interviewer_knowledge}：
 * 角色与文档的多对多关系。
 *
 * <p>该角色参与面试时，只有这里挂载的文档才会进入检索候选，
 * 这样「Java 岗考官」不会被塞进算法岗的知识。
 *
 * <p>用自增 id + 唯一约束而不是复合主键，与项目其它关联表保持一致，
 * 也便于按单行增删。
 */
@Entity
@Table(name = "interviewer_knowledge")
public class InterviewerKnowledge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "interviewer_code", length = 32, nullable = false)
    private String interviewerCode;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

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

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }
}

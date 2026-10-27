package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 岗位知识库的「片段」，对应 DDL {@code knowledge_chunk}：一份文档切分后的一小块。
 *
 * <p>检索命中的单位是片段而不是整篇文档，这样注入提示词的上下文才装得下
 * （一本 JVM 调优手册整份塞进去会挤掉对话历史）。
 *
 * <p>{@code fingerprint} 是「heading + 正文」的 FNV-1a 64 位哈希，
 * 重新导入时内容没变就跳过重算向量——embedding 是外部调用，能省则省。
 */
@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 所属文档，逻辑外键（不建 FK 约束，删文档由服务层级联清理）。 */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 文档内序号，从 0 开始；检索结果按它排序即可还原原文顺序。 */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 标题面包屑（如「第3章 &gt; 3.2 内存泄漏排查」），拼提示词时给模型一个位置感。 */
    @Column(name = "heading", length = 300)
    private String heading;

    @Column(name = "text", columnDefinition = "CLOB", nullable = false)
    private String text;

    /** 正文字数，前端展示「片段多长」用；可从 text 现算，但列表接口不想把 CLOB 全捞出来。 */
    @Column(name = "char_count")
    private Integer charCount;

    /** 内容指纹，见类注释：用于判断「这份片段要不要重算向量」。 */
    @Column(name = "fingerprint", nullable = false)
    private Long fingerprint;

    /**
     * 假设问题（K6 HyPE）：模型为这段正文生成的「这段话能回答哪些问题」，
     * 一行一个问题。只用于检索（进 BM25 词表、进向量），不进提示词、不算正文。
     */
    @Column(name = "hype_questions", columnDefinition = "CLOB")
    private String hypeQuestions;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Long getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(Long fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getHypeQuestions() {
        return hypeQuestions;
    }

    public void setHypeQuestions(String hypeQuestions) {
        this.hypeQuestions = hypeQuestions;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

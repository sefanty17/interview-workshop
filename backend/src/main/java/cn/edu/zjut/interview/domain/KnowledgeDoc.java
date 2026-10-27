package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 岗位知识库的「文档」，对应 DDL {@code knowledge_doc}：一本 md 就是一类知识点。
 *
 * <p>和世界书（{@code interviewer.worldbook} 一整份 YAML 全文注入）的区别在于：
 * 文档在这里只是<b>语料源</b>，真正注入提示词的是它切出来的片段（{@link KnowledgeChunk}）
 * 经向量检索后的命中结果，所以正文不落在这张表，只有元信息。
 *
 * <p>{@code code} 是跨重启稳定的业务标识（如 {@code jvm-tuning}），
 * 导入脚本按它 upsert，因此加了唯一约束。
 */
@Entity
@Table(name = "knowledge_doc")
public class KnowledgeDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 稳定标识，脚本据此幂等覆盖；改名会让已挂载的角色卡失联，所以不允许随便变。 */
    @Column(name = "code", length = 64, nullable = false, unique = true)
    private String code;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 分类（如「后端」「算法」），只用于前端分组展示，不参与检索。 */
    @Column(name = "category", length = 64)
    private String category;

    /** md 文件来源路径，纯展示用：排查「哪份文件导进来的」时不必翻日志。 */
    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Column(name = "description", columnDefinition = "CLOB")
    private String description;

    /** 关掉即不参与检索，但片段与向量保留，避免临时停用时还要重建。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

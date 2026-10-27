package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 片段向量，对应 DDL {@code knowledge_vector}：md 才是真相源，这张表只是<b>可丢弃的缓存</b>，
 * 清空后由 reindex 从 {@code knowledge_chunk} 重建。
 *
 * <p>主键就是 {@code chunk_id}（一个片段只有一条向量），所以这里<b>不加</b>
 * {@code @GeneratedValue}：id 由服务层手工赋成片段的 id，删片段时顺手删向量，
 * 不会出现「孤儿向量」需要额外维护映射关系。
 *
 * <p>{@code model} 与 {@code dim} 必须成对过滤：换了 embedding 模型，
 * 旧向量和新查询向量不在同一空间，余弦相似度没有意义。
 */
@Entity
@Table(name = "knowledge_vector")
public class KnowledgeVector {

    /** 手工赋值的主键，等于所属片段的 id，故意不加 @GeneratedValue。 */
    @Id
    @Column(name = "chunk_id")
    private Long chunkId;

    /** 冗余存一份 doc_id：按文档批量删向量时不必先查 chunk 表。 */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 产出该向量的模型名（如 hashing-512 / bge-m3），写死在这里才能事后判断向量是否可比。 */
    @Column(name = "model", length = 64, nullable = false)
    private String model;

    /** 向量维度，与 model 一起用于过滤；换模型后维度往往也变。 */
    @Column(name = "dim", nullable = false)
    private Integer dim;

    /**
     * 向量本体：float 序列化成小端字节序的 byte[]。
     * 用 BLOB 而不是 float 数组列，是因为 H2 没有数组类型，且检索在内存里做，
     * 不需要数据库参与相似度计算。
     */
    @Column(name = "embedding", columnDefinition = "BLOB", nullable = false)
    private byte[] embedding;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getDim() {
        return dim;
    }

    public void setDim(Integer dim) {
        this.dim = dim;
    }

    public byte[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(byte[] embedding) {
        this.embedding = embedding;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

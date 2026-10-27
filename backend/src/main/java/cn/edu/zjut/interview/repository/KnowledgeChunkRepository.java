package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** {@code knowledge_chunk}：文档切分后的片段。 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /** 按文档取全部片段，序号升序即原文顺序（重建向量、拼上下文都用这个顺序）。 */
    List<KnowledgeChunk> findByDocIdOrderByChunkIndexAsc(Long docId);

    /** 文档列表里展示「切了几段」，避免把 CLOB 正文全捞出来。 */
    long countByDocId(Long docId);

    /** 覆盖式导入前清空旧片段；派生删除，调用方负责事务。 */
    void deleteByDocId(Long docId);
}

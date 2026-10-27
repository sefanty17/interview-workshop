package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.KnowledgeVector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** {@code knowledge_vector}：片段向量缓存，可随时清空重建。 */
public interface KnowledgeVectorRepository extends JpaRepository<KnowledgeVector, Long> {

    /** 按文档取向量：文档列表要统计条数，并取第一条的 model/dim 展示。 */
    List<KnowledgeVector> findByDocId(Long docId);

    /** 检索时必须与「模型 + 维度」成对过滤，否则跨模型的向量不可比。 */
    List<KnowledgeVector> findByModelAndDim(String model, int dim);

    /** 健康检查里报「当前模型覆盖了多少条向量」，不必把向量本体读进内存。 */
    long countByModelAndDim(String model, int dim);

    /** 覆盖式导入 / 重建前清空该文档的向量；派生删除，调用方负责事务。 */
    void deleteByDocId(Long docId);
}

package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.KnowledgeDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** {@code knowledge_doc}：岗位知识库的文档元信息。 */
public interface KnowledgeDocRepository extends JpaRepository<KnowledgeDoc, Long> {

    /** 按业务标识查文档：接口路径与导入脚本都以 code 为入口。 */
    Optional<KnowledgeDoc> findByCode(String code);

    /** 列表按建库顺序返回，前端勾选列表的顺序才不会每次刷新都变。 */
    List<KnowledgeDoc> findAllByOrderByIdAsc();

    /** 建文档前查重，用于把重复 code 拦成 409 而不是数据库唯一约束的 500。 */
    boolean existsByCode(String code);
}

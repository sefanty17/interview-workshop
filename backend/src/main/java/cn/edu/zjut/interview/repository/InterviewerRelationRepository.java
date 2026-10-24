package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewerRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** {@code interviewer_relation}：角色对各方的出厂关系（对齐 {@code relations.yml}）。 */
public interface InterviewerRelationRepository extends JpaRepository<InterviewerRelation, Long> {

    /** 某个角色的整份关系表。 */
    List<InterviewerRelation> findByInterviewerCode(String interviewerCode);

    /**
     * 整份覆盖前先清空该角色的关系（对齐 {@code role_relations::save}）。
     *
     * <p>批量删除走 JPQL 直接下 DELETE，不经过持久化上下文；调用方（{@link InterviewerRelationService#save}）
     * 已经加了 {@code @Transactional}，无需在这里再标事务。
     */
    @Modifying
    @Query("delete from InterviewerRelation r where r.interviewerCode = :code")
    void deleteByInterviewerCode(@Param("code") String code);
}

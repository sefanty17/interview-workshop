package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * {@code interview_evaluation}：场次级评估结论（本项目扩展（课设指标 ③））。
 */
public interface InterviewEvaluationRepository extends JpaRepository<InterviewEvaluation, Long> {

    /**
     * 某场次的评估；唯一约束保证最多一行，这里仍返回 List 是为了与
     * {@link InterviewEvaluationDimensionRepository#findBySessionIdOrderByIdAsc} 的取法一致，
     * 调用方不用在「Optional 还是 List」之间切换。
     */
    List<InterviewEvaluation> findBySessionId(Long sessionId);

    /**
     * 一批场次的评估（成长曲线一次取全，不做 N+1）。
     *
     * <p>没有评估的场次不会出现在结果里，调用方按 sessionId 映射后自然跳过。
     */
    List<InterviewEvaluation> findBySessionIdIn(Collection<Long> sessionIds);

    /**
     * 重跑前先清空该场次的旧评估（先删后插，靠唯一约束保证一场只有一份）。
     *
     * <p>批量删除走 JPQL 直接下 DELETE，不经过持久化上下文；调用方
     * （{@code EvaluationService#evaluate}）已经加了 {@code @Transactional}。
     */
    @Modifying
    @Query("delete from InterviewEvaluation e where e.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);
}

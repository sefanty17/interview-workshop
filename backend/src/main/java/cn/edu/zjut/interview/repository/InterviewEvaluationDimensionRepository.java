package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewEvaluationDimension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * {@code interview_evaluation_dimension}：四维度各自的分与评语（本项目扩展（课设指标 ③））。
 */
public interface InterviewEvaluationDimensionRepository extends JpaRepository<InterviewEvaluationDimension, Long> {

    /**
     * 某场次的四个维度，按 id 升序（= 写入顺序 technical / depth / logic / fit）。
     *
     * <p>成长曲线按 {@code dim_code} 查趋势，所以顺序必须稳定，不能依赖数据库返回次序。
     */
    List<InterviewEvaluationDimension> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 重跑前先清空该场次的旧维度行（先删后插）。 */
    @Modifying
    @Query("delete from InterviewEvaluationDimension d where d.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);
}

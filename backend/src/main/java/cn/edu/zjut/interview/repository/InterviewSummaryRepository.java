package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * {@code interview_summary}：场次级对话总结（本项目扩展，issue #4）。
 */
public interface InterviewSummaryRepository extends JpaRepository<InterviewSummary, Long> {

    /** 某场次的总结；唯一约束保证最多一行，仍返回 List 与评估仓库的取法保持一致。 */
    List<InterviewSummary> findBySessionId(Long sessionId);

    /**
     * 重跑前先清空该场次的旧总结（先删后插，靠唯一约束保证一场只有一份）。
     *
     * <p>批量删除走 JPQL 直接下 DELETE；调用方在事务里调用（{@code SummaryService} 的保存方法）。
     */
    @Modifying
    @Query("delete from InterviewSummary s where s.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") Long sessionId);
}

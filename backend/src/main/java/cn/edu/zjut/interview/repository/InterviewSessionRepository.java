package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    /** 用户的历史场次，新的在前。 */
    List<InterviewSession> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 场次列表（规格 B5 §6.1）：按状态过滤，新的在前。 */
    List<InterviewSession> findByStatusOrderByCreatedAtDesc(String status);

    /** 场次列表（不带过滤）。 */
    List<InterviewSession> findAllByOrderByCreatedAtDesc();

    /**
     * 场次列表（不带过滤，排除试玩场次）——规格 E5b §1.1。
     *
     * <p>试玩场次是编辑器里的一次性演示：不进面试记录，也不该占列表位置。
     */
    List<InterviewSession> findAllByPreviewFalseOrderByCreatedAtDesc();

    /** 场次列表（按状态过滤，排除试玩场次）——规格 E5b §1.1。 */
    List<InterviewSession> findByStatusAndPreviewFalseOrderByCreatedAtDesc(String status);

    /**
     * 所有试玩场次——规格 E5b §1.3 的「建之前清场」用。
     *
     * <p>上一次试玩若是直接关浏览器走掉的，场次会残留，这里在建新试玩前统一收掉。
     */
    List<InterviewSession> findAllByPreviewTrueOrderByCreatedAtDesc();

    /**
     * 引用某剧本的场次（规格 E5a §1.2）。
     *
     * <p>{@code interview_session.script_id} 是普通列、没有外键约束，删剧本前必须靠这里
     * 把这些引用解绑，否则「删了剧本 → 历史场次点开始面试」会报剧本不存在。
     */
    List<InterviewSession> findByScriptId(Long scriptId);
}

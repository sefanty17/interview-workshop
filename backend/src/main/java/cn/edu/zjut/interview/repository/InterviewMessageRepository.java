package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InterviewMessageRepository extends JpaRepository<InterviewMessage, Long> {

    /** 按 seq 升序取整场台词，MemoryBuilder 直接消费这个顺序。 */
    List<InterviewMessage> findBySessionIdOrderBySeqAsc(Long sessionId);

    /** 前端刷新用：只取 {@code seq > afterSeq} 的增量。 */
    List<InterviewMessage> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(Long sessionId, Integer afterSeq);

    /** 判断 system 行是否已经落过（ChatService 第 2 步的兜底检查）。 */
    boolean existsBySessionIdAndRole(Long sessionId, String role);

    /** 统计某角色的条数（验收 / 联调用）。 */
    long countBySessionIdAndRole(Long sessionId, String role);

    /** 当前最大 seq；空场次返回 0，省掉一次 null 判断。 */
    @Query("select coalesce(max(m.seq), 0) from InterviewMessage m where m.sessionId = :sessionId")
    int findMaxSeqBySessionId(@Param("sessionId") Long sessionId);

    /** 台词条数（剧本引擎的 progress 锚点要报 line_count）。 */
    long countBySessionId(Long sessionId);

    /**
     * 回溯用：删掉 {@code seq >= fromSeq} 的所有台词（含锚点那条用户消息本身）。
     *
     * <p>对齐 {@code rollback_conversation} 的 {@code line_list.truncate(idx)}：
     * 截断到第 N 条用户消息<b>之前</b>，那条用户消息也一并删掉，玩家才能重说一遍。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from InterviewMessage m where m.sessionId = :sessionId and m.seq >= :fromSeq")
    int deleteFromSeq(@Param("sessionId") Long sessionId, @Param("fromSeq") Integer fromSeq);

    /** 最近 20 条台词，倒序取回来再翻正：ai_judged 的【对话记录】用。 */
    List<InterviewMessage> findTop20BySessionIdOrderBySeqDesc(Long sessionId);

    /**
     * 某个玩家身份被多少条台词引用（删身份前的引用检查）。
     *
     * <p>对齐原版 {@code find_saves_using_identity}：有引用就不许删。
     * 只查 {@code interview_message} —— {@code interview_session} 没有 identity_id 列。
     */
    long countByIdentityId(String identityId);
}

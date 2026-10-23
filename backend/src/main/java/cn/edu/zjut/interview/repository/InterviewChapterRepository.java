package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewChapter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InterviewChapterRepository extends JpaRepository<InterviewChapter, Long> {

    /** 剧本的阶段按 order_num 升序（面试编排段就按这个顺序拼流程）。 */
    List<InterviewChapter> findByScriptIdOrderByOrderNumAsc(Long scriptId);

    /** UNIQUE(script_id, code)。 */
    Optional<InterviewChapter> findByScriptIdAndCode(Long scriptId, String code);

    /** 重导剧本时清掉旧章节（规格 B5 §2.1 幂等）；调用方负责先 flush 再插入同 code 的行。 */
    void deleteByScriptId(Long scriptId);
}

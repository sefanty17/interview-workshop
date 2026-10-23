package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewScript;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InterviewScriptRepository extends JpaRepository<InterviewScript, Long> {

    /** 按业务编码查剧本（code 上有 UNIQUE 约束）。 */
    Optional<InterviewScript> findByCode(String code);

    List<InterviewScript> findByEnabledTrueOrderByOrderNumAsc();

    /** 资源接口用：全量按 order_num 升序（规格 B6 §1.2）。 */
    List<InterviewScript> findAllByOrderByOrderNumAsc();

    /** 资源接口用：按岗位过滤（规格 B6 §1.2）。 */
    List<InterviewScript> findAllByJobCodeOrderByOrderNumAsc(String jobCode);
}

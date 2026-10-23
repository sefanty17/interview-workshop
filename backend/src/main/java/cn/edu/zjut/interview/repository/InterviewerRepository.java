package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.Interviewer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InterviewerRepository extends JpaRepository<Interviewer, Long> {

    /** 按业务编码查角色卡（code 上有 UNIQUE 约束）。 */
    Optional<Interviewer> findByCode(String code);

    /** 角色列表页用：只取启用的，按 sort_order 升序。 */
    List<Interviewer> findByEnabledTrueOrderBySortOrderAsc();

    /** 资源接口用：全量按 sort_order 升序（规格 B6 §1.2）。 */
    List<Interviewer> findAllByOrderBySortOrderAsc();

    /**
     * 兜底取第一个面试官（规格 E5b §1.3 第 2 步）。
     *
     * <p>剧本没绑面试官、连 {@code strict} 都没有时（例如种子数据被清空的自建库），
     * 试玩至少还要能起一个场次，不能因为「找不到人设」直接 500。
     */
    Optional<Interviewer> findFirstByOrderByIdAsc();
}

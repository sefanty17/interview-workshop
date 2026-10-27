package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.InterviewerKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** {@code interviewer_knowledge}：角色卡挂载的岗位知识文档。 */
public interface InterviewerKnowledgeRepository extends JpaRepository<InterviewerKnowledge, Long> {

    /** 某个角色勾选的全部文档，按勾选顺序返回即可（检索侧不关心顺序）。 */
    List<InterviewerKnowledge> findByInterviewerCode(String interviewerCode);

    /** 整份覆盖勾选结果前先清空；派生删除，调用方负责事务。 */
    void deleteByInterviewerCode(String interviewerCode);

    /** 文档被删时级联清掉挂载，避免角色卡指向不存在的文档。 */
    void deleteByDocId(Long docId);
}

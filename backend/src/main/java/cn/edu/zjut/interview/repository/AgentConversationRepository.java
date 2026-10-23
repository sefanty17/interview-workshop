package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.AgentConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** AI 助手会话表（规格 E7a1 §3.1）。 */
public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {

    /** 某剧本 key 下的全部会话（原版 {@code db.rs} 没有这个方法，本项目按 script_key 建索引后补）。 */
    List<AgentConversation> findByScriptKey(String scriptKey);

    /** 会话列表：最近更新在前（对齐原版 {@code list_conversations} 的 {@code order_by_desc(UpdatedAt)}）。 */
    List<AgentConversation> findAllByOrderByUpdatedAtDesc();
}

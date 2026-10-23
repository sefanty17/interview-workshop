package cn.edu.zjut.interview.repository;

import cn.edu.zjut.interview.domain.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** AI 助手消息表（规格 E7a1 §3.1）。 */
public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    /** 某会话的全部消息，按 id 升序（对齐原版 {@code list_messages} 的 {@code order_by_asc(Id)}）。 */
    List<AgentMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** 删会话时连带删消息（对齐原版 {@code delete_conversation} 先删消息）。 */
    void deleteByConversationId(Long conversationId);

    /** 「回溯」：删掉 id &gt;= fromId 的消息，把对话退回到该消息之前。 */
    void deleteByConversationIdAndIdGreaterThanEqual(Long conversationId, Long fromId);
}

package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.domain.AgentConversation;
import cn.edu.zjut.interview.domain.AgentMessage;
import cn.edu.zjut.interview.repository.AgentConversationRepository;
import cn.edu.zjut.interview.repository.AgentMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill Agent 会话/消息落表（规格 E7a1 §6.2 的 9 条）。
 *
 * <p>不加 {@code @Transactional}：要验的就是「真的落库了」，回滚会让断言变成自证。
 * 代价是得自己清数据 —— 两张表都是本批新建的，{@code @AfterEach} 按 id 兜底删干净，
 * 不碰任何既有表。
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillAgentServiceTest {

    @Autowired
    private SkillAgentService service;

    @Autowired
    private AgentConversationRepository conversationRepository;

    @Autowired
    private AgentMessageRepository messageRepository;

    /** 本轮测试建过的会话 id（含已被用例自己删掉的），{@code @AfterEach} 统一清掉。 */
    private final List<Long> createdConversations = new ArrayList<>();

    /**
     * 兜底清理：本类建过的会话（含用例自己删过的）连同消息一起删掉。
     *
     * <p>用 {@code deleteAll} / {@code deleteById} 而不是 {@code deleteByConversationId}：
     * 后者是派生删除方法，没有事务包裹，测试类又不加 {@code @Transactional}，
     * 会报「No EntityManager with actual transaction」。
     */
    @AfterEach
    void cleanUp() {
        for (Long id : createdConversations) {
            messageRepository.deleteAll(messageRepository.findByConversationIdOrderByIdAsc(id));
            conversationRepository.findById(id).ifPresent(c -> conversationRepository.deleteById(c.getId()));
        }
        createdConversations.clear();
    }

    // 1. 建会话 → 列表返回 1 条，按 updated_at 倒序
    @Test
    void createConversationThenList() {
        ConversationInfo first = newConversation("会话-1", null);
        List<ConversationInfo> list = service.listConversations();
        assertEquals(1, list.size(), "库里只有刚建的这一条会话");
        assertEquals(first.id(), list.get(0).id());

        // 再建一条并回头 touch 第一条：列表应把最近更新的排在最前
        ConversationInfo second = newConversation("会话-2", null);
        service.renameConversation(first.id(), "会话-1-改名");
        List<ConversationInfo> two = service.listConversations();
        assertEquals(2, two.size());
        assertEquals(first.id(), two.get(0).id(), "updated_at 倒序：刚改名（touch）的那条在前");
        assertEquals(second.id(), two.get(1).id());
    }

    // 2. 建会话带 scriptKey → findByScriptKey 能查到
    @Test
    void createConversationWithScriptKey() {
        ConversationInfo info = newConversation("带剧本的会话", "java-strict");

        List<AgentConversation> found = conversationRepository.findByScriptKey("java-strict");
        assertEquals(1, found.size());
        assertEquals(info.id(), found.get(0).getId());
        assertEquals("java-strict", found.get(0).getScriptKey());
        assertEquals("java-strict", service.resolveScriptKey(info.id()), "resolve 直接返回库里绑的 key");
    }

    // 3. 改标题 → 重新读取为新值
    @Test
    void renameConversation() {
        ConversationInfo info = newConversation("原标题", null);

        service.renameConversation(info.id(), "新标题");

        AgentConversation reloaded = conversationRepository.findById(info.id()).orElseThrow();
        assertEquals("新标题", reloaded.getTitle());
        assertEquals("新标题", service.listConversations().stream()
                .filter(c -> c.id().equals(info.id()))
                .findFirst().orElseThrow().title());

        assertThrows(ResponseStatusException.class, () -> service.renameConversation(info.id(), "  "),
                "空标题要被拒（原版同款：避免清空标题后首轮又自动命名）");
    }

    // 4. 插 3 条消息 → getMessages 按 id 升序返回 3 条
    @Test
    void insertMessagesThenListAsc() {
        ConversationInfo info = newConversation("有消息的会话", null);
        insertMessage(info.id(), "user", "第一句");
        insertMessage(info.id(), "assistant", "第二句");
        insertMessage(info.id(), "assistant", "第三句");

        List<PersistedMessage> messages = service.getMessages(info.id());
        assertEquals(3, messages.size());
        assertEquals(List.of("第一句", "第二句", "第三句"), messages.stream().map(PersistedMessage::content).toList());
        assertTrue(messages.get(0).id() < messages.get(1).id(), "按 id 升序");
        assertTrue(messages.get(1).id() < messages.get(2).id());
        assertEquals("user", messages.get(0).role());
        assertNull(messages.get(0).toolCalls(), "没写工具调用时 tool_calls 解析结果是 null");
    }

    // 5. clear → 消息清空但会话还在
    @Test
    void clearConversationKeepsConversation() {
        ConversationInfo info = newConversation("待清空的会话", null);
        insertMessage(info.id(), "user", "你好");
        insertMessage(info.id(), "assistant", "在的");

        service.clearConversation(info.id());

        assertTrue(service.getMessages(info.id()).isEmpty(), "消息清空");
        assertTrue(conversationRepository.findById(info.id()).isPresent(), "会话本身还在");
    }

    // 6. rewind(fromId) → 只删 fromId 及之后，之前的保留
    @Test
    void rewindDeletesFromId() {
        ConversationInfo info = newConversation("待回溯的会话", null);
        Long first = insertMessage(info.id(), "user", "保留-1");
        Long second = insertMessage(info.id(), "assistant", "保留-2");
        Long third = insertMessage(info.id(), "user", "删掉-3");
        Long fourth = insertMessage(info.id(), "assistant", "删掉-4");

        service.rewind(info.id(), third);

        List<PersistedMessage> left = service.getMessages(info.id());
        assertEquals(2, left.size());
        assertEquals(List.of(first, second), left.stream().map(PersistedMessage::id).toList());
        assertTrue(messageRepository.findById(third).isEmpty());
        assertTrue(messageRepository.findById(fourth).isEmpty());
    }

    // 7. 删会话 → 会话与其消息一起没了
    @Test
    void deleteConversationRemovesMessages() {
        ConversationInfo info = newConversation("待删的会话", null);
        insertMessage(info.id(), "user", "一句话");
        insertMessage(info.id(), "assistant", "另一句");

        service.deleteConversation(info.id());

        assertTrue(conversationRepository.findById(info.id()).isEmpty(), "会话没了");
        assertTrue(messageRepository.findByConversationIdOrderByIdAsc(info.id()).isEmpty(), "消息跟着没了");
    }

    // 8. 删除不存在的会话 → 抛异常（404）
    @Test
    void deleteMissingConversationIsNotFound() {
        long missing = 9_999_999L;
        assertTrue(conversationRepository.findById(missing).isEmpty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.deleteConversation(missing));
        assertEquals(404, ex.getStatusCode().value());
    }

    // 9. 收尾清干净临时数据（@AfterEach 兜底）
    @Test
    void cleanupRemovesEveryTemporaryRow() {
        ConversationInfo first = newConversation("临时会话-1", null);
        ConversationInfo second = newConversation("临时会话-2", "java-strict");
        insertMessage(first.id(), "user", "临时消息");
        insertMessage(second.id(), "assistant", "另一条临时消息");

        // 直接调 @AfterEach 用的同一段逻辑，验它真能把两张表清干净（不留脏数据给别的测试类）
        cleanUp();

        assertTrue(conversationRepository.findAll().isEmpty(), "会话表清干净");
        assertTrue(messageRepository.findAll().isEmpty(), "消息表清干净");
    }

    // ───────────────── 工具 ─────────────────

    private ConversationInfo newConversation(String title, String scriptKey) {
        ConversationInfo info = service.createConversation(title, scriptKey);
        createdConversations.add(info.id());
        return info;
    }

    private Long insertMessage(Long conversationId, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(message).getId();
    }
}

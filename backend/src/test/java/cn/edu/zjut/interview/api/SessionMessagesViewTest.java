package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.PlayerIdentity;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmOptions;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import cn.edu.zjut.interview.repository.PlayerIdentityRepository;
import cn.edu.zjut.interview.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 历史对话流的数据契约（批次 7，对齐 {@code convert_init_lines} 落库顺序）。
 *
 * <p>三件以前没有、页面非有不可的东西：
 * <ul>
 *   <li>{@code displayName} —— 这一句显示成谁说的，合并块靠它分组；</li>
 *   <li>{@code identityId} —— 换过身份后历史里并存多个「我」，靠它区分；</li>
 *   <li>{@code userMessageSeq} —— 第几条用户消息，批次 8 的回溯截断用它定位。
 *       只在控制器里按 {@code seq} 现算，不落物理列（落了会和落库时序打架）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SessionMessagesViewTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatService chatService;

    @Autowired
    private InterviewMessageRepository messageRepository;

    @Autowired
    private PlayerIdentityRepository identityRepository;

    @Autowired
    private InterviewerRepository interviewerRepository;

    @MockBean
    private LlmClient llmClient;

    /**
     * 本类会真的落会话 / 台词 / 身份，跑完必须清干净。
     *
     * <p>{@code SchemaMappingTest} 断言「种子库里没有场次」，测试类执行顺序一变就会被这里
     * 留下的数据打破 —— 污染者负责清理，不去改那个断言。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        identityRepository.deleteAll();
    }

    @Autowired
    private cn.edu.zjut.interview.repository.InterviewSessionRepository sessionRepository;

    @Test
    void 台词带出身份与显示名且用户消息按序编号() throws Exception {
        CreateSessionResponse created =
                chatService.createSession(new CreateSessionRequest("strict", "java", null));
        Long sessionId = created.sessionId();

        String identityId = "id-" + UUID.randomUUID();
        PlayerIdentity identity = new PlayerIdentity();
        identity.setIdentityId(identityId);
        identity.setName("小桉");
        identity.setSubtitle("应届");
        identity.setPrompt("");
        identity.setRelations("{}");
        identity.setIsCurrent(Boolean.FALSE);
        identity.setUpdatedAt(LocalDateTime.now());
        identityRepository.save(identity);

        save(sessionId, 1, InterviewMessage.ROLE_USER, "我做过一个在线商城", identityId, "小桉", 0);
        save(sessionId, 2, InterviewMessage.ROLE_ASSISTANT, "【疑惑】你做过商城？", null, "风雪", 1);
        save(sessionId, 3, InterviewMessage.ROLE_SYSTEM, "（面试官翻开简历）", null, null, null);
        save(sessionId, 4, InterviewMessage.ROLE_USER, "用的 Spring Boot", identityId, "小桉", 0);
        save(sessionId, 5, InterviewMessage.ROLE_ASSISTANT, "【平静】持久层呢。", null, "风雪", 1);

        mockMvc.perform(get("/api/sessions/" + sessionId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                // 玩家行带身份与显示名
                .andExpect(jsonPath("$[0].identityId").value(identityId))
                .andExpect(jsonPath("$[0].displayName").value("小桉"))
                .andExpect(jsonPath("$[0].senderRoleId").value(0))
                // 第一条用户消息 = 1
                .andExpect(jsonPath("$[0].userMessageSeq").value(1))
                // AI 行不带身份，显示名是角色名
                .andExpect(jsonPath("$[1].identityId").doesNotExist())
                .andExpect(jsonPath("$[1].displayName").value("风雪"))
                .andExpect(jsonPath("$[1].userMessageSeq").doesNotExist())
                // 旁白 / system：没有显示名，也不参与用户消息编号
                .andExpect(jsonPath("$[2].displayName").doesNotExist())
                .andExpect(jsonPath("$[2].userMessageSeq").doesNotExist())
                // 第二条用户消息 = 2（编号按 seq 顺序累加，不是按数据库 id）
                .andExpect(jsonPath("$[3].userMessageSeq").value(2));
    }

    /**
     * 面试官台词也要带 {@code display_name}（对齐 {@code line.display_name}）。
     *
     * <p>历史对话流按 {@code displayName} 分组，面试官行留空会被当成旁白：斜体、不冠名。
     * 2026-10-25 端到端实测发现并补上。
     */
    @Test
    void 面试官台词带角色名作为显示名() throws Exception {
        CreateSessionResponse created =
                chatService.createSession(new CreateSessionRequest("strict", "java", null));
        // 开场白只在剧本会话落库（批次 4 的改动），自由对话这里直接从回复里取
        String expectedName = interviewerRepository.findByCode("strict").orElseThrow().getName();

        doAnswer(invocation -> {
            LlmOptions options = invocation.getArgument(1);
            Consumer<String> onDelta = invocation.getArgument(2);
            if (options.onReasoning() != null) {
                options.onReasoning().accept("先看他的技术栈。");
            }
            onDelta.accept("【冷淡】说具体点。");
            return null;
        }).when(llmClient).stream(anyList(), any(LlmOptions.class), any());

        chatService.processMessage(created.sessionId(), "我做过一个在线商城");

        long deadline = System.currentTimeMillis() + 5_000L;
        List<InterviewMessage> messages;
        while (true) {
            messages = messageRepository.findBySessionIdOrderBySeqAsc(created.sessionId());
            boolean done = messages.stream().anyMatch(
                    m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole()) && m.getThinking() != null);
            if (done) break;
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待异步落库超时，当前消息数：" + messages.size());
            }
            Thread.sleep(50);
        }

        List<InterviewMessage> assistant = messages.stream()
                .filter(m -> InterviewMessage.ROLE_ASSISTANT.equals(m.getRole())).toList();
        assertFalse(assistant.isEmpty(), "本轮至少要落一条 assistant 台词");
        assistant.forEach(m -> assertEquals(expectedName, m.getDisplayName(),
                "面试官行的 display_name 必须是角色名，否则历史页会当成旁白渲染"));

        // 玩家行仍然带身份名，system 行仍然为 null
        messages.stream().filter(m -> InterviewMessage.ROLE_USER.equals(m.getRole()))
                .forEach(m -> assertNotNull(m.getDisplayName(), "玩家行必带名字"));
        messages.stream().filter(m -> InterviewMessage.ROLE_SYSTEM.equals(m.getRole()))
                .forEach(m -> assertEquals(null, m.getDisplayName(), "system 行不冠名"));

        mockMvc.perform(get("/api/sessions/" + created.sessionId() + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role=='assistant')].displayName")
                        .value(org.hamcrest.Matchers.hasItem(expectedName)));
    }

    private void save(Long sessionId, int seq, String role, String content,
                      String identityId, String displayName, Integer senderRoleId) {
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(sessionId);
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content);
        message.setIdentityId(identityId);
        message.setDisplayName(displayName);
        message.setSenderRoleId(senderRoleId);
        message.setMsgType("text");
        message.setCreatedAt(LocalDateTime.now());
        messageRepository.save(message);
    }
}

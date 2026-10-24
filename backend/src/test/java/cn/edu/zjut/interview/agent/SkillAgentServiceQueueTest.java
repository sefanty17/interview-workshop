package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.repository.AgentConversationRepository;
import cn.edu.zjut.interview.repository.AgentMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * P0：{@code runChatTurn} 的「末条注入 + 多步队列一轮内逐项执行」（规格
 * {@code .workbuddy/plans/P0规格-core_run_chat与队列.md} 验收第 4 条）。
 *
 * <p>LLM 用替身：第 1 次调用返回 {@code submit_plan}（队列两项），之后每次返回一段正文。
 * 断言三件事——推了 2 条 {@code item_start}（1/2、2/2）；第 2 项的 system prompt 换成了
 * 第 2 项的角色手册；每次请求的最后一条 user 是本项注入的 core_run_chat 模板。
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillAgentServiceQueueTest {

    /** 注入给 service 的模板：带标记，便于在请求里认出"最后一条 user 是不是它"。 */
    private static final String MARKER = "【单测-core_run_chat】";
    private static final String TEMPLATE = MARKER + "这一项={} 第 {}/{} 项 对象=「{}」 章节落 Chapters/<id>.yaml";

    @Autowired
    private SkillAgentService service;
    @Autowired
    private AgentConversationRepository conversationRepository;
    @Autowired
    private AgentMessageRepository messageRepository;

    @MockBean
    private SkillAgentLlmClient llmClient;

    private Long conversationId;

    @AfterEach
    void cleanUp() {
        if (conversationId != null) {
            messageRepository.deleteAll(messageRepository.findByConversationIdOrderByIdAsc(conversationId));
            conversationRepository.findById(conversationId)
                    .ifPresent(c -> conversationRepository.deleteById(c.getId()));
            conversationId = null;
        }
    }

    @Test
    void twoItemQueueRunsBothItemsWithOwnSystemAndOwnLastUser() throws Exception {
        conversationId = service.createConversation("两项队列", null).id();
        when(llmClient.isUsable()).thenReturn(true);

        List<List<SkillAgentLlmClient.Msg>> requests = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<SkillAgentLlmClient.Msg> messages = invocation.getArgument(0);
            requests.add(new ArrayList<>(messages));
            int call = calls.getAndIncrement();
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(3);
            if (call == 0) {
                String plan = """
                        {"tasks":[{"kind":"outline","target":""},
                                  {"kind":"write_chapter","target":"03"}],
                         "reason":"先写大纲再落盘第 3 章"}""";
                return new SkillAgentLlmClient.Completion("", "", List.of(
                        new SkillAgentLlmClient.ToolCall("call_plan", "submit_plan", plan)),
                        "tool_calls", null);
            }
            String text = "第 " + call + " 次补全的正文";
            if (onDelta != null) {
                onDelta.accept(text);
            }
            return new SkillAgentLlmClient.Completion(text, "", List.of(), "stop",
                    new SkillAgentLlmClient.Usage(10, 5, 15, 0));
        }).when(llmClient).completeStream(anyList(), anyList(), any(), any(), any(), any());

        List<SkillAgentEvent> events = new CopyOnWriteArrayList<>();
        service.runChatTurn(conversationId, "先写大纲再写第 3 章", capturingEmitter(events), TEMPLATE);

        // ① 1 次路由 + 2 项各自的 1 次补全；两项都没落地，各多一次【代码核对】二跑 = 5 次
        assertEquals(5, requests.size(), "路由 1 次 + 每项各 1 次补全 + 每项各 1 次【代码核对】二跑");

        // ② 推了 2 条 item_start，index 1/2、2/2
        List<SkillAgentEvent> starts = events.stream()
                .filter(e -> "item_start".equals(e.type())).toList();
        assertEquals(2, starts.size(), "两项队列要推 2 条 item_start");
        assertEquals(1, starts.get(0).index());
        assertEquals(2, starts.get(0).total());
        assertEquals(2, starts.get(1).index());
        assertEquals(2, starts.get(1).total());
        assertTrue(starts.get(1).title().contains("转成 YAML"), "第 2 项标题是本项的类型标签+对象：" + starts.get(1).title());
        assertTrue(starts.get(1).title().contains("03"));

        // ③ 第 2 项的 system prompt 换成了第 2 项的角色手册（它排在各自的二跑请求之前）
        String firstSystem = requests.get(1).get(0).content;
        String secondSystem = requests.get(3).get(0).content;
        assertTrue(firstSystem.contains("本轮你扮演的是「编剧」"), "第 1 项（outline）用编剧角色");
        assertTrue(secondSystem.contains("本轮你扮演的是「落盘」"), "第 2 项（write_chapter）必须换成落盘角色");
        assertTrue(secondSystem.contains("delete_file"), "落盘的工具白名单里要有 delete_file");
        assertTrue(!firstSystem.contains("delete_file"), "编剧的白名单里没有 delete_file");

        // ④ 两项的首次请求：最后一条 user 都是本项的 core_run_chat（且排在任务书之后）
        for (int i = 1; i <= 3; i += 2) {
            List<SkillAgentLlmClient.Msg> msgs = requests.get(i);
            SkillAgentLlmClient.Msg last = msgs.get(msgs.size() - 1);
            assertEquals("user", last.role, "最后一条必须是 user");
            assertNotNull(last.content);
            assertTrue(last.content.startsWith(MARKER), "最后一条 user 就是本项的 core_run_chat，实际：" + last.content);
            assertTrue(last.content.contains("interview_chapter 表里 code=<id> 的那一行"),
                    "原版路径 Chapters/<id>.yaml 已换成本项目落点：" + last.content);
            // 倒数第二条是脚本上下文（任务书），不是 core_run_chat
            SkillAgentLlmClient.Msg before = msgs.get(msgs.size() - 2);
            assertEquals("user", before.role);
            assertTrue(before.content.contains("【本轮任务】"), "任务书仍然保留，排在 core_run_chat 之前");
        }
        String firstOwn = requests.get(1).get(requests.get(1).size() - 1).content;
        String secondOwn = requests.get(3).get(requests.get(3).size() - 1).content;
        assertTrue(firstOwn.contains("这一项=编写大纲"), "第 1 项填的是类型标签：" + firstOwn);
        assertTrue(firstOwn.contains("第 1/2 项"));
        assertTrue(firstOwn.contains("（未指明）"), "第 1 项没有对象，填「（未指明）」");
        assertTrue(secondOwn.contains("这一项=转成 YAML：03"), "第 2 项填的是「类型：对象」：" + secondOwn);
        assertTrue(secondOwn.contains("第 2/2 项"));
        assertTrue(secondOwn.contains("「03」"));

        // ④' 二跑请求的最后一条 user 是【代码核对】（两项都没落地，各触发一次）
        for (int i = 2; i <= 4; i += 2) {
            List<SkillAgentLlmClient.Msg> second = requests.get(i);
            SkillAgentLlmClient.Msg last = second.get(second.size() - 1);
            assertEquals("user", last.role);
            assertTrue(last.content.startsWith("【代码核对】"), "二跑喂的是【代码核对】：" + last.content);
            assertTrue(last.content.contains("没落地就不算做完"), "文案逐字照抄原版：" + last.content);
        }

        // ⑤ done 里两项的回复都拼上了
        SkillAgentEvent done = events.stream().filter(e -> "done".equals(e.type()))
                .reduce((a, b) -> b).orElseThrow();
        assertTrue(done.finalText().contains("第 1 次补全的正文"), "第 1 项的正文在 done 里：" + done.finalText());
        assertTrue(done.finalText().contains("第 2 次补全的正文"), "第 2 项的正文也在 done 里：" + done.finalText());
        assertTrue(done.finalText().contains("【第 1/2 项】") && done.finalText().contains("【第 2/2 项】"),
                "两项的分段头都在：" + done.finalText());
    }

    /** 只收集事件的 emitter（不真的写网络连接）。 */
    static SseEmitter capturingEmitter(List<SkillAgentEvent> sink) {
        return new SseEmitter(0L) {
            @Override
            public void send(SseEmitter.SseEventBuilder builder) {
                for (SseEmitter.DataWithMediaType item : builder.build()) {
                    Object value = item.getData();
                    if (value instanceof SkillAgentEvent event) {
                        sink.add(event);
                    } else if (value != null) {
                        sink.add(new SkillAgentEvent("unknown",
                                new String(String.valueOf(value).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                                null, null, null, null, null, null, null, null, null, null, null, null, null, null));
                    }
                }
            }
        };
    }
}

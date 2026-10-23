package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.engine.events.SetVariableEvent;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link SetVariableEvent} 对齐 {@code set_variable_event.rs}：
 * {@code =} / {@code +=} / {@code -=} 写入 {@code variable_info}、条件不满足的 option 跳过、
 * 非 {@code set_var} 动作忽略；全程不发 SSE。
 */
class SetVariableEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EventSink sink = mock(EventSink.class);
    private final InterviewMessageRepository messageRepository = mock(InterviewMessageRepository.class);

    @Test
    void writesAssignAndArithmeticIntoVariables() throws Exception {
        ObjectNode vars = objectMapper.createObjectNode();

        String next = new SetVariableEvent().execute(ctx("""
                {"type": "set_variable",
                 "options": [{"actions": [
                    {"type": "set_var", "content": "count += 1"},
                    {"type": "set_var", "content": "flag = true"},
                    {"type": "set_var", "content": "name = \\"蔡智豪\\""}
                 ]}]}
                """, vars));

        assertNull(next, "set_variable 不跳章、不阻塞");
        assertEquals(1, vars.path("count").asInt());
        assertTrue(vars.path("count").isIntegralNumber(), "整数加法必须保持整数（1 而不是 1.0）");
        assertTrue(vars.path("flag").asBoolean());
        assertEquals("蔡智豪", vars.path("name").asText());
        verifyNoInteractions(sink);
    }

    @Test
    void arithmeticKeepsExistingIntegerType() throws Exception {
        ObjectNode vars = objectMapper.createObjectNode();
        vars.put("hp", 5);

        new SetVariableEvent().execute(ctx("""
                {"type": "set_variable",
                 "options": [{"actions": [{"type": "set_var", "content": "hp -= 2"}]}]}
                """, vars));

        assertEquals(3, vars.path("hp").asInt());
        assertTrue(vars.path("hp").isIntegralNumber());
    }

    @Test
    void skipsOptionWhoseConditionFails() throws Exception {
        ObjectNode vars = objectMapper.createObjectNode();

        new SetVariableEvent().execute(ctx("""
                {"type": "set_variable",
                 "options": [
                   {"condition": "ready == true", "actions": [{"type": "set_var", "content": "locked = 1"}]},
                   {"condition": "", "actions": [{"type": "set_var", "content": "open = 1"}]}
                 ]}
                """, vars));

        assertNull(vars.get("locked"), "condition 不满足的 option 整段跳过");
        assertEquals(1, vars.path("open").asInt());
    }

    @Test
    void ignoresUnsupportedActionType() throws Exception {
        ObjectNode vars = objectMapper.createObjectNode();

        new SetVariableEvent().execute(ctx("""
                {"type": "set_variable",
                 "options": [{"actions": [
                    {"type": "add_line", "content": "这不该生效"},
                    {"type": "set_var", "content": "无法解析的动作"}
                 ]}]}
                """, vars));

        assertEquals(0, vars.size(), "不支持的动作与解析失败的动作都不改动变量表");
    }

    private EngineContext ctx(String json, ObjectNode vars) {
        InterviewSession session = new InterviewSession();
        session.setId(1L);
        session.setCurrentChapter("main");
        JsonNode event;
        try {
            event = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new EngineContext(session, event, sink, messageRepository,
                mock(InterviewChapterRepository.class), mock(InterviewerRepository.class),
                null, objectMapper, "候选人", vars, "main");
    }
}

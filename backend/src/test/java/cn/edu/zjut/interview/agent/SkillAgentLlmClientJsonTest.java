package cn.edu.zjut.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话循环请求体的序列化契约。
 *
 * <p>两个真实崩点都在这里钉死，都是「编辑器 AI 一句话都产不出来」的根因：
 * <ol>
 *   <li>{@code tool_calls} 数组早前每个调用被写成两个元素（一个空对象 + 一个缺 id 的），
 *       DeepSeek 回 422 {@code messages[3]: missing field `id`}；</li>
 *   <li>tool 结果行的调用 id 早前被落进 {@code tool_calls} 列（CLOB），
 *       而不是 {@code tool_call_id}（VARCHAR(64)），一发起工具调用就超长报错。</li>
 * </ol>
 */
class SkillAgentLlmClientJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toolCallsArrayHasOneEntryPerCall() throws Exception {
        SkillAgentLlmClient.Msg msg = SkillAgentLlmClient.Msg.assistantWithTools("",
                List.of(new SkillAgentLlmClient.ToolCall("call_1", "list_files", "{\"path\":\".\"}"),
                        new SkillAgentLlmClient.ToolCall("call_2", "read_file", "{\"path\":\"a.md\"}")));

        JsonNode json = msg.toJson(mapper);
        JsonNode calls = json.path("tool_calls");

        assertEquals(2, calls.size(), "两个调用就是两个数组元素（不许出现空对象）");
        for (int i = 0; i < calls.size(); i++) {
            JsonNode call = calls.get(i);
            assertEquals("function", call.path("type").asText());
            assertTrue(call.hasNonNull("id"), "每个调用都必须有 id，否则 DeepSeek 报 missing field `id`");
            assertTrue(call.path("function").hasNonNull("name"));
            assertTrue(call.path("function").hasNonNull("arguments"));
        }
        assertEquals("call_1", calls.get(0).path("id").asText());
        assertEquals("list_files", calls.get(0).path("function").path("name").asText());
        assertEquals("call_2", calls.get(1).path("id").asText());
    }

    @Test
    void toolResultCarriesToolCallId() throws Exception {
        JsonNode json = SkillAgentLlmClient.Msg.toolResult("call_1", "📂 ok").toJson(mapper);

        assertEquals("tool", json.path("role").asText());
        assertEquals("call_1", json.path("tool_call_id").asText());
        assertTrue(json.path("tool_calls").isMissingNode(), "tool 行没有 tool_calls 字段");
    }
}

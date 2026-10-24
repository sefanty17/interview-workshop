package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.agent.SkillAgentRouter.TaskKind;
import cn.edu.zjut.interview.llm.LlmOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-6：思考模式按任务类型给（{@code role.rs:130-148}）+ 请求体恒注入
 * {@code thinking: {type}}（{@code genai_provider.rs:391-418}）+ {@code lastWritten}
 * 取「设计稿顺序上最后一个已落盘章」（{@code stage/mod.rs:98-103}）。
 */
class ThinkingModeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 五个开、五个关，逐个钉死（原版 role.rs:136-148 的十个分支）。 */
    @Test
    void thinkingFollowsTaskKindNotStage() {
        for (TaskKind kind : List.of(TaskKind.COLLECT, TaskKind.SUMMARIZE, TaskKind.OUTLINE,
                TaskKind.REVISE_OUTLINE, TaskKind.DRAFT_CHAPTER)) {
            assertTrue(kind.thinking(), kind.key() + " 要开思考");
        }
        for (TaskKind kind : List.of(TaskKind.CHAT, TaskKind.WRITE_CHAPTER, TaskKind.REVISE_CHAPTER,
                TaskKind.CHECK_ASSETS, TaskKind.POLISH)) {
            assertFalse(kind.thinking(), kind.key() + " 不开思考");
        }
    }

    /** 请求体恒带 thinking 字段；值为 null（没指定）时按关下发。 */
    @Test
    void requestBodyAlwaysCarriesThinking() {
        assertEquals("enabled", thinkingOf(Boolean.TRUE).path("type").asText());
        assertEquals("disabled", thinkingOf(Boolean.FALSE).path("type").asText());
        assertEquals("disabled", thinkingOf(null).path("type").asText(),
                "null 视作关闭（对齐 LlmProviderConfig.enable_thinking 默认 false）");
    }

    @Test
    void thinkingEnabledTreatsNullAsFalse() {
        assertTrue(new LlmOptions("m", 1.0, 1.0, 100, 60, Boolean.TRUE).thinkingEnabled());
        assertFalse(new LlmOptions("m", 1.0, 1.0, 100, 60, Boolean.FALSE).thinkingEnabled());
        assertFalse(new LlmOptions("m", 1.0, 1.0, 100, 60, null).thinkingEnabled());
    }

    /** written 里有设计稿之外的旧章时，上一章必须按设计稿顺序取，不是 written 的最后一个。 */
    @Test
    void lastWrittenFollowsPlanOrder(@TempDir Path dir) throws Exception {
        Path scriptDir = dir.resolve("scripts").resolve("demo");
        Files.createDirectories(scriptDir.resolve(".agent"));
        Files.writeString(scriptDir.resolve(".agent").resolve("design.md"), """
                ## 一
                id: 01

                ## 二
                id: 02""".stripIndent(), StandardCharsets.UTF_8);

        StageFacts withStray = StageFacts.collect("demo", scriptDir, List.of("01", "02", "09"));
        assertEquals("02", withStray.lastWritten(), "09 不在设计稿里，不能当上一章");

        StageFacts partial = StageFacts.collect("demo", scriptDir, List.of("01", "09"));
        assertEquals("01", partial.lastWritten());

        StageFacts none = StageFacts.collect("demo", scriptDir, List.of("09"));
        assertNull(none.lastWritten(), "一章都不在设计稿里 → 没有上一章");
    }

    private JsonNode thinkingOf(Boolean value) {
        ObjectNode body = mapper.createObjectNode();
        SkillAgentLlmClient.putThinking(body, new LlmOptions("m", 1.0, 1.0, 100, 60, value));
        return body.path("thinking");
    }
}

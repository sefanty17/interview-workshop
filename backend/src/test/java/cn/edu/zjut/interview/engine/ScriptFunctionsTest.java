package cn.edu.zjut.interview.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ScriptFunctions} 对齐 {@code script_function.rs:205 match_ai_response_options}
 * 与 {@code script_function.rs:20 replace_placeholder}。
 */
class ScriptFunctionsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void matchesOptionNameBySubstring() throws Exception {
        JsonNode options = objectMapper.readTree("""
                [{"name": "deep", "next": "deep"}, {"name": "basic_more", "next": "basic_more"}]
                """);
        assertEquals("deep", ScriptFunctions.matchAiResponseOptions("我选 deep", options));
	}

    @Test
    void fallsBackToDefaultOption() throws Exception {
        JsonNode options = objectMapper.readTree("""
                [{"name": "deep", "next": "deep"},
                 {"name": "basic_more", "next": "basic_more", "default": true}]
                """);
        assertEquals("basic_more", ScriptFunctions.matchAiResponseOptions("说不清楚", options));
    }

    @Test
    void returnsNullWhenNothingMatches() throws Exception {
        JsonNode options = objectMapper.readTree("""
                [{"name": "deep", "next": "deep"}]
                """);
        assertNull(ScriptFunctions.matchAiResponseOptions("随便", options));
    }

    @Test
    void replacesPlayerPlaceholder() {
        assertEquals("你好，候选人", ScriptFunctions.replacePlaceholder("你好，%player%", "候选人"));
        assertEquals("没有占位符", ScriptFunctions.replacePlaceholder("没有占位符", "候选人"));
    }
}

package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.ScriptCharacters;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 规格 B6 §2.2：剧本列表 / 岗位过滤 / 详情章节 / events 可被 Jackson 解析。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ScriptControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsFourScripts() throws Exception {
        mockMvc.perform(get("/api/scripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void listCarriesCharactersForEveryScript() throws Exception {
        mockMvc.perform(get("/api/scripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].characters", hasSize(1)))
                .andExpect(jsonPath("$[0].characters[0]").value("MAIN"));
    }

    @Test
    void detailCarriesCharacters() throws Exception {
        mockMvc.perform(get("/api/scripts/java-strict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characters", hasSize(1)))
                .andExpect(jsonPath("$.characters[0]").value("MAIN"));
    }

    @Test
    void charactersParsesJsonArrayAndFallsBackToEmpty() {
        assertEquals(List.of("MAIN", "观察员"), ScriptCharacters.parse("[\"MAIN\", \"观察员\"]"));
        assertEquals(List.of(), ScriptCharacters.parse(null));
        assertEquals(List.of(), ScriptCharacters.parse("  "));
        assertEquals(List.of(), ScriptCharacters.parse("不是 JSON"));
        assertEquals(List.of(), ScriptCharacters.parse("{\"character\": \"MAIN\"}"), "不是数组就当空");
    }

    @Test
    void listFiltersByJob() throws Exception {
        mockMvc.perform(get("/api/scripts").param("job", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].jobCode").value("java"))
                .andExpect(jsonPath("$[1].jobCode").value("java"));
    }

    @Test
    void detailReturnsChaptersInOrder() throws Exception {
        String body = mockMvc.perform(get("/api/scripts/java-strict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("java-strict"))
                .andExpect(jsonPath("$.interviewerCode").value("strict"))
                .andExpect(jsonPath("$.chapters", hasSize(6)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode chapters = JSON.readTree(body).get("chapters");
        assertEquals(6, chapters.size());
        assertEquals("intro", chapters.get(0).get("code").asText());

        for (JsonNode chapter : chapters) {
            JsonNode events = JSON.readTree(chapter.get("events").asText());
            assertTrue(events.isArray(), "chapter " + chapter.get("code").asText() + " 的 events 不是 JSON 数组");
        }
    }

    @Test
    void unknownCodeReturns404() throws Exception {
        mockMvc.perform(get("/api/scripts/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.code").value("nope"));
    }
}

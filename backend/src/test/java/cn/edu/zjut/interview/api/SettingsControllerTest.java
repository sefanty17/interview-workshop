package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.repository.UserSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设置接口（规格 B4-BE §二、§五 2~5）。
 *
 * <p>{@code GET /tree} 给两层结构（分类 → 子分类）、17 项且值是默认值；{@code PUT} 成功后再 GET 能读回；
 * 越界值与未知 key 都是 400 且 body 里带 {@code error} 文案。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserSettingRepository userSettingRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void treeReturnsTwoCategoriesAndSeventeenSettings() throws Exception {
        userSettingRepository.deleteAll();

        MvcResult result = mockMvc.perform(get("/api/settings/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.categories[0].id").value("llm"))
                .andExpect(jsonPath("$.categories[0].subcategories[0].id").value("advanced"))
                .andExpect(jsonPath("$.categories[1].id").value("features"))
                .andExpect(jsonPath("$.categories[1].subcategories.length()").value(3))
                .andReturn();

        JsonNode tree = objectMapper.readTree(result.getResponse().getContentAsString());
        int total = 0;
        for (JsonNode category : tree.path("categories")) {
            assertTrue(category.hasNonNull("id") && category.hasNonNull("label"), "分类要有 id 与 label");
            for (JsonNode sub : category.path("subcategories")) {
                assertTrue(sub.hasNonNull("id") && sub.hasNonNull("label"), "子分类要有 id 与 label");
                total += sub.path("settings").size();
                for (JsonNode setting : sub.path("settings")) {
                    assertEquals(setting.path("defaultValue").asText(), setting.path("value").asText());
                    assertTrue(setting.path("value").isTextual(), "value 一律是 String");
                    assertTrue(java.util.List.of("text", "number", "bool")
                                    .contains(setting.path("type").asText()),
                            "type 只能是 text/number/bool：" + setting.path("type").asText());
                }
            }
        }
        assertEquals(17, total);
    }

    @Test
    void putTemperatureThenTreeShowsNewValue() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"llm.temperature\":\"1.5\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/settings/tree"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode tree = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("1.5", valueOf(tree, "llm.temperature"));
    }

    @Test
    void outOfRangeTemperatureIsBadRequestWithRangeText() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"llm.temperature\":\"9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("0.0–2.0")));
    }

    @Test
    void unknownKeyIsBadRequest() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"foo.bar\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("未知配置项")));
    }

    @Test
    void emptyBodyIsBadRequest() throws Exception {
        mockMvc.perform(put("/api/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 从配置树里取某个 key 的当前值。 */
    private static String valueOf(JsonNode tree, String key) {
        for (JsonNode category : tree.path("categories")) {
            for (JsonNode sub : category.path("subcategories")) {
                for (JsonNode setting : sub.path("settings")) {
                    if (key.equals(setting.path("key").asText())) {
                        return setting.path("value").asText();
                    }
                }
            }
        }
        throw new AssertionError("配置树里没有 " + key);
    }
}

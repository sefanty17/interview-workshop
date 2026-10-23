package cn.edu.zjut.interview.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 规格 B6 §2.1：面试官列表 4 条、详情带完整人设、未知 code 404。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class InterviewerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsFourInterviewers() throws Exception {
        mockMvc.perform(get("/api/interviewers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].code", containsInAnyOrder("strict", "friendly", "pressure", "expert")))
                // 列表不吐长文本
                .andExpect(jsonPath("$[0].personaPrompt").doesNotExist());
    }

    @Test
    void detailReturnsPersonaPrompt() throws Exception {
        mockMvc.perform(get("/api/interviewers/strict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("strict"))
                .andExpect(jsonPath("$.personaPrompt").isNotEmpty())
                .andExpect(jsonPath("$.name").isNotEmpty());
    }

    @Test
    void unknownCodeReturns404() throws Exception {
        mockMvc.perform(get("/api/interviewers/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"))
                .andExpect(jsonPath("$.code").value("nope"));
    }
}

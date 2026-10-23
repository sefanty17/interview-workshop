package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.repository.UserProfileRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 规格 B6 §2.3：空库返回兜底身份；PUT 之后 GET 能读到新值。
 *
 * <p>测试库启动时 data-h2.sql 已 MERGE 了一条身份，所以先清表再验兜底——
 * 否则测的是种子数据，不是兜底逻辑。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileRepository profileRepository;

    @Test
    @Order(1)
    void emptyTableReturnsFallbackProfile() throws Exception {
        profileRepository.deleteAll();

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("候选人"));
    }

    @Test
    @Order(2)
    void putThenGetReturnsUpdatedProfile() throws Exception {
        String body = """
                {
                  "nickname": "林一",
                  "signature": "在找 Java 后端实习",
                  "jobTarget": "java",
                  "school": "浙江工业大学",
                  "major": "软件工程",
                  "grade": "大三",
                  "studentNo": "302024315000",
                  "resumeSummary": "两段后端项目经历"
                }
                """;

        mockMvc.perform(put("/api/profile").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("林一"));

        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("林一"))
                .andExpect(jsonPath("$.signature").value("在找 Java 后端实习"))
                .andExpect(jsonPath("$.jobTarget").value("java"))
                .andExpect(jsonPath("$.school").value("浙江工业大学"))
                .andExpect(jsonPath("$.major").value("软件工程"))
                .andExpect(jsonPath("$.grade").value("大三"))
                .andExpect(jsonPath("$.studentNo").value("302024315000"))
                .andExpect(jsonPath("$.resumeSummary").value("两段后端项目经历"));
    }
}

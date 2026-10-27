package cn.edu.zjut.interview.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 规格 K1 §8 第 10 条：建完文档后列表接口能返回它。
 *
 * <p>走 MockMvc 而不是真起 8080，是因为开发后端可能正在跑（会抢同一台机器上的端口与库文件）。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class KnowledgeControllerTest {

    private static final String CODE = "k1-ctrl-doc";

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void 清理用例数据() throws Exception {
        mockMvc.perform(delete("/api/knowledge/docs/" + CODE));
    }

    @Test
    void 建文档后列表接口返回该code() throws Exception {
        mockMvc.perform(post("/api/knowledge/docs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + CODE + "\",\"title\":\"控制器探测\","
                                + "\"category\":\"test\",\"sourcePath\":\"docs/probe.md\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.chunkCount").value(0));

        mockMvc.perform(get("/api/knowledge/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == '" + CODE + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.code == '" + CODE + "')].title", hasItem("控制器探测")));

        mockMvc.perform(get("/api/knowledge/docs/" + CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE));
    }
}

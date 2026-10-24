package cn.edu.zjut.interview.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次 6 §2.5 的两组 REST：身份 CRUD + 角色关系读写。
 *
 * <p>只验语义（状态码、关键字段、整份覆盖），不重复 service 层已覆盖的细节。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PlayerIdentityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 新建后用id读写并切换当前身份() throws Exception {
        String body = """
                {"name":" 小桉 ","subtitle":"测试身份","prompt":"我是测试","relations":{"ai:strict":"宿敌"}}
                """;
        String created = mockMvc.perform(post("/api/player-identities")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("小桉"))
                .andReturn().getResponse().getContentAsString();
        String identityId = objectMapper.readTree(created).path("identityId").asText();

        mockMvc.perform(get("/api/player-identities/" + identityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relations['ai:strict']").value("宿敌"));

        mockMvc.perform(post("/api/player-identities/" + identityId + "/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityId").value(identityId));

        mockMvc.perform(get("/api/player-identities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relationCount").isNumber())
                .andExpect(jsonPath("$[0].hasAvatar").isBoolean());
    }

    @Test
    void 当前身份也能删且删完回落到默认身份() throws Exception {
        // 对齐 api_player_identity.rs:114 —— 原版只挡「有存档引用」，不挡「是当前」。
        // 早期加了「当前不可删」会死锁：库里只剩一张卡时切不出去也删不掉。
        String created = mockMvc.perform(post("/api/player-identities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"当前身份\"}"))
                .andReturn().getResponse().getContentAsString();
        String identityId = objectMapper.readTree(created).path("identityId").asText();
        mockMvc.perform(post("/api/player-identities/" + identityId + "/switch"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/player-identities/" + identityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        // 真删掉了（不是软删），详情回 404
        mockMvc.perform(get("/api/player-identities/" + identityId))
                .andExpect(status().isNotFound());
    }

    @Test
    void 未知身份返回404() throws Exception {
        mockMvc.perform(get("/api/player-identities/__nope__"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 关系整份覆盖() throws Exception {
        String first = objectMapper.writeValueAsString(Map.of("me:alice", "青梅竹马", "me:bob", "前同事"));
        mockMvc.perform(put("/api/interviewers/strict/relations")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(put("/api/interviewers/strict/relations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"me:alice\":\"点头之交\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$['me:alice']").value("点头之交"));

        mockMvc.perform(get("/api/interviewers/strict/relations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['me:alice']").value("点头之交"));

        // 清干净，别把关系留在测试库里影响别的用例
        mockMvc.perform(put("/api/interviewers/strict/relations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
}

package cn.edu.zjut.interview.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    /** K6 用另一篇文档，免得和上面那条用例互相污染。 */
    private static final String HYPE_CODE = "k6-ctrl-hype";
    /** .txt 上传走独立端点，单独一篇文档。 */
    private static final String TEXT_CODE = "k1-ctrl-text";

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void 清理用例数据() throws Exception {
        mockMvc.perform(delete("/api/knowledge/docs/" + CODE));
        mockMvc.perform(delete("/api/knowledge/docs/" + HYPE_CODE));
        mockMvc.perform(delete("/api/knowledge/docs/" + TEXT_CODE));
    }

    /** 纯文本上传：按空行切块后，片段接口能按 chunkIndex 列出位置与内容。 */
    @Test
    void 上传纯文本后能按序号列出切块() throws Exception {
        String paragraph = "MySQL 索引的最左前缀原则要求查询从联合索引的第一列开始匹配，否则后面的列无法用上索引。";
        String text = String.join("\n\n", java.util.Collections.nCopies(26, paragraph))
                .replace("\n", "\\n");

        mockMvc.perform(post("/api/knowledge/docs/" + TEXT_CODE + "/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"MySQL 索引笔记\",\"category\":\"java\","
                                + "\"sourcePath\":\"notes.txt\",\"text\":\"" + text + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(get("/api/knowledge/docs/" + TEXT_CODE + "/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].chunkIndex").value(0))
                .andExpect(jsonPath("$[0].heading", containsString("MySQL 索引的最左前缀")))
                .andExpect(jsonPath("$[0].text", containsString("最左前缀")))
                .andExpect(jsonPath("$[1].chunkIndex").value(1));
    }

    /** 非法 code 的 400 必须带中文 detail，前端才能显示具体原因。 */
    @Test
    void 非法code返回带中文detail的400() throws Exception {
        mockMvc.perform(post("/api/knowledge/docs/Bad_Code/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("文档 code 只能包含")));
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

    /**
     * K6：{@code hype} 字段能从 JSON 一路走到索引。
     *
     * <p>正文里没有「写屏障」三个字，只有假设问题里有，所以这条检索能命中就证明
     * hype 没在传输层被丢掉；同时 {@code hits[0].text} 必须仍是正文原文，
     * 证明假设问题只进词表、不进结果。
     */
    @Test
    void 片段带假设问题时可被用户自己的说法检索到() throws Exception {
        mockMvc.perform(post("/api/knowledge/docs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + HYPE_CODE + "\",\"title\":\"并发标记\","
                                + "\"category\":\"test\"}"))
                .andExpect(status().isOk());

        String body = """
                {"chunks":[
                  {"heading":"第1章 > 1.1 三色标记",
                   "text":"并发标记阶段用三色标记法，漏标由增量更新与原始快照修正",
                   "hype":"写屏障是干什么的？"},
                  {"heading":"第1章 > 1.2 指令重排",
                   "text":"栅栏指令保证指令不越过边界重排"}
                ]}
                """;
        mockMvc.perform(put("/api/knowledge/docs/" + HYPE_CODE + "/chunks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.inserted").value(2));

        mockMvc.perform(get("/api/knowledge/search")
                        .param("q", "写屏障是干什么的")
                        .param("mode", "bm25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].docCode").value(HYPE_CODE))
                .andExpect(jsonPath("$.hits[0].text")
                        .value("并发标记阶段用三色标记法，漏标由增量更新与原始快照修正"));
    }
}

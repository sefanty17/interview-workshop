package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.dto.EditorChapterContent;
import cn.edu.zjut.interview.dto.EditorCreateScriptRequest;
import cn.edu.zjut.interview.dto.EditorScriptDetail;
import cn.edu.zjut.interview.dto.EditorScriptPackage;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.service.ScriptEditorService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E5a §1.3：新建 / 删除剧本。
 *
 * <p>用 {@code @SpringBootTest} + test profile（内存库），不碰开发库
 * {@code backend/data/interview.mv.db}。每个用例自己造数据、自己清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScriptEditorServiceScriptTest {

    /** 造出来的剧本一律用这个前缀，清理时按前缀兜底扫一遍。 */
    private static final String PREFIX = "e5a-test-";

    @Autowired
    private ScriptEditorService service;

    @Autowired
    private InterviewScriptRepository scriptRepository;

    @Autowired
    private InterviewChapterRepository chapterRepository;

    private static EditorCreateScriptRequest request(String code, String introChapter) {
        return new EditorCreateScriptRequest(
                code,
                "临时剧本",
                "单元测试用",
                "java",
                "校招",
                "一线互联网大厂",
                "friendly",
                introChapter);
    }

    // ---- 1 / 2：包信息与开场章节 ----

    @Test
    void 新建剧本返回的包信息与开场章节符合预期() {
        String code = PREFIX + "create";
        try {
            EditorScriptPackage pkg = service.createScript(request(code, "opening"));

            assertEquals(code, pkg.key(), "key 等于传入的 code");
            assertEquals(1, pkg.chapterCount(), "新建剧本带一个开场章节");
            assertEquals("临时剧本", pkg.scriptName());

            EditorScriptDetail detail = service.readScript(code);
            assertEquals(1, detail.chapters().size(), "chapters 只有开场这一章");
            assertEquals("opening", detail.chapters().get(0).id(), "开场章节 id 用的是传入的 introChapter");

            EditorChapterContent intro = service.readChapter(code, "opening");
            assertEquals(1, intro.events().size(), "开场章节自带一条事件");
            JsonNode event = intro.events().get(0);
            assertEquals("chapter_end", event.get("type").asText());
            assertEquals("end", event.get("next_chapter").asText());
        } finally {
            cleanup(code);
        }
    }

    // ---- 3：面试官绑定 ----

    @Test
    void 新建时指定的面试官会落库() {
        String code = PREFIX + "interviewer";
        try {
            service.createScript(request(code, "intro"));

            EditorScriptDetail detail = service.readScript(code);
            Map<String, Object> config = detail.storyConfig();
            assertEquals("friendly", config.get("interviewer_code"));
        } finally {
            cleanup(code);
        }
    }

    // ---- 4：重复 code ----

    @Test
    void 重复的剧本编码被拒绝() {
        String code = PREFIX + "dup";
        try {
            service.createScript(request(code, "intro"));

            EditorBadRequestException e = assertThrows(EditorBadRequestException.class,
                    () -> service.createScript(request(code, "intro")));
            assertTrue(e.getMessage().contains("已存在"), "消息应说明编码已存在：" + e.getMessage());
        } finally {
            cleanup(code);
        }
    }

    // ---- 5：非法 code ----

    @Test
    void 大写开头的剧本编码被拒绝() {
        assertThrows(EditorBadRequestException.class,
                () -> service.createScript(request("Java-Strict", "intro")));
    }

    // ---- 6：不存在的面试官 ----

    @Test
    void 不存在的面试官被拒绝() {
        String code = PREFIX + "nobody";
        EditorCreateScriptRequest bad = new EditorCreateScriptRequest(
                code, "临时剧本", null, "java", "校招", null, "__nope__", "intro");
        try {
            assertThrows(EditorBadRequestException.class, () -> service.createScript(bad));
            assertTrue(scriptRepository.findByCode(code).isEmpty(), "校验失败不该留下半条剧本");
        } finally {
            cleanup(code);
        }
    }

    // ---- 7：删除 ----

    @Test
    void 删除剧本后列表与章节都不再有它() {
        String code = PREFIX + "delete";
        service.createScript(request(code, "intro"));
        Long scriptId = scriptRepository.findByCode(code).orElseThrow().getId();

        service.deleteScript(code);

        List<String> keys = service.listScripts().stream().map(EditorScriptPackage::key).toList();
        assertFalse(keys.contains(code), "列表里不该还有被删的剧本");
        assertTrue(chapterRepository.findByScriptIdOrderByOrderNumAsc(scriptId).isEmpty(),
                "章节应随剧本一起删掉");
    }

    // ---- 8：删不存在的 ----

    @Test
    void 删除不存在的剧本抛NotFound() {
        assertThrows(EditorNotFoundException.class, () -> service.deleteScript(PREFIX + "missing"));
    }

    /** 用例自己造的数据自己清；删不掉的（比如已被删过）忽略。 */
    private void cleanup(String code) {
        if (scriptRepository.findByCode(code).isPresent()) {
            service.deleteScript(code);
        }
    }
}

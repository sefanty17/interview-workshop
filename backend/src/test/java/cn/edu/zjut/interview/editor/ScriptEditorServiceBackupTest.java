package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.dto.EditorChapterContent;
import cn.edu.zjut.interview.dto.EditorWriteChapterRequest;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.service.ScriptEditorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E1 §11.4 d：覆盖前留备份的落库验证。
 *
 * <p>用 {@code @SpringBootTest} + test profile（内存库），不碰开发库
 * {@code backend/data/interview.mv.db}。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScriptEditorServiceBackupTest {

    @Autowired
    private ScriptEditorService service;

    @Autowired
    private InterviewChapterRepository chapterRepository;

    @Autowired
    private InterviewScriptRepository scriptRepository;

    @Test
    void 写入章节会把旧events存进备份列() {
        InterviewChapter chapter = chapterRepository.findAll().stream()
                .filter(c -> c.getEvents() != null && !c.getEvents().isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("种子数据里没有可用章节"));

        String scriptCode = scriptRepository.findById(chapter.getScriptId())
                .orElseThrow()
                .getCode();
        String before = chapter.getEvents();
        EditorChapterContent content = service.readChapter(scriptCode, chapter.getCode());

        service.writeChapter(scriptCode, chapter.getCode(),
                new EditorWriteChapterRequest(content.name(), content.events()));

        InterviewChapter reloaded = chapterRepository.findById(chapter.getId()).orElseThrow();
        assertEquals(before, reloaded.getEventsBackup(), "写入前的 events 应该留在 events_backup 列");
        assertNotNull(reloaded.getEvents());
        // 写回同样内容后 events 语义不变（字符串格式可能被 Jackson 压紧）
        assertEquals(content.events(), service.readChapter(scriptCode, chapter.getCode()).events());
        assertTrue(reloaded.getEvents().startsWith("["), "events 仍是 JSON 数组");
    }
}

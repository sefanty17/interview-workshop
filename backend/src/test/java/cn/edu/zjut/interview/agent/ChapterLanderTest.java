package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落盘桥接（{@link ChapterLander}）与承接事实（{@link ScriptFacts}）的行为验证。
 *
 * <p>这一批修的是一个真实断点：编辑器 AI 写的章节 YAML 只落在沙箱文件里，
 * 而引擎与校验器读的是 {@code interview_chapter} 表。用例直接从「写一份 YAML」
 * 验到「库里有了这一章」，不靠 mock。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChapterLanderTest {

    @Autowired
    private ChapterLander lander;

    @Autowired
    private InterviewScriptRepository scriptRepository;

    @Autowired
    private InterviewChapterRepository chapterRepository;

    /** 本类建的剧本 id，{@code @AfterEach} 统一删干净。 */
    private final List<Long> createdScripts = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdScripts) {
            chapterRepository.deleteAll(chapterRepository.findByScriptIdOrderByOrderNumAsc(id));
            scriptRepository.deleteById(id);
        }
        createdScripts.clear();
    }

    private InterviewScript newScript(String code) {
        InterviewScript script = new InterviewScript();
        script.setCode(code);
        script.setScriptName("测试剧本 " + code);
        script.setIntroChapter("01");
        script.setEnabled(Boolean.TRUE);
        InterviewScript saved = scriptRepository.saveAndFlush(script);
        createdScripts.add(saved.getId());
        return saved;
    }

    private static final String CHAPTER_YAML = """
            name: 自我介绍
            events:
              - type: narration
                text: 面试开始了
              - type: chapter_end
                end_type: linear
                next_chapter: end
            """;

    // ───────────────────────── 落盘 ─────────────────────────

    @Test
    void 写章节YAML会落进章节表() {
        InterviewScript script = newScript("lander-new");

        ChapterLander.Landing landing = lander.land(script.getCode(),
                "scripts/lander-new/Chapters/01.yaml", CHAPTER_YAML);

        assertTrue(landing.ok(), "落盘应当成功：" + landing.message());
        Optional<InterviewChapter> saved =
                chapterRepository.findByScriptIdAndCode(script.getId(), "01");
        assertTrue(saved.isPresent(), "库里应当出现章节 01");
        assertEquals("自我介绍", saved.get().getName(), "name 从 YAML 的 name: 来");
        assertTrue(saved.get().getEvents().startsWith("["), "events 存成 JSON 数组");
        assertTrue(saved.get().getEvents().contains("narration"), "events 内容被搬进库");
        assertFalse(saved.get().getEvents().contains("next_chapter_typo"), "不该凭空多出字段");
    }

    @Test
    void 再次落盘同一章会留备份并更新内容() {
        InterviewScript script = newScript("lander-again");
        String path = "scripts/lander-again/Chapters/02.yaml";

        lander.land(script.getCode(), path, CHAPTER_YAML);
        InterviewChapter first = chapterRepository
                .findByScriptIdAndCode(script.getId(), "02").orElseThrow();
        String before = first.getEvents();
        assertNull(first.getEventsBackup(), "第一次写没有备份");

        String second = """
                name: 项目经历
                events:
                  - type: narration
                    text: 换个话题
                """;
        lander.land(script.getCode(), path, second);

        InterviewChapter reloaded = chapterRepository
                .findByScriptIdAndCode(script.getId(), "02").orElseThrow();
        assertEquals(before, reloaded.getEventsBackup(), "覆盖前的内容留在 events_backup");
        assertEquals("项目经历", reloaded.getName());
        assertTrue(reloaded.getEvents().contains("换个话题"));
    }

    @Test
    void 没绑定剧本时不落库并给出原因() {
        ChapterLander.Landing landing = lander.land(null,
                "scripts/whatever/Chapters/01.yaml", CHAPTER_YAML);

        assertFalse(landing.ok(), "没绑定剧本应当失败");
        assertTrue(landing.message().contains("还没绑定剧本"), "要说清为什么：" + landing.message());
    }

    @Test
    void 剧本不存在时不落库() {
        ChapterLander.Landing landing = lander.land("no-such-script",
                "scripts/no-such-script/Chapters/01.yaml", CHAPTER_YAML);

        assertFalse(landing.ok());
        assertTrue(landing.message().contains("不存在"), "要说清剧本不存在：" + landing.message());
    }

    @Test
    void 不是章节文件时跳过且不算失败() {
        ChapterLander.Landing landing = lander.land("any", ".agent/design.md", "# 设计稿");
        assertTrue(landing.ok(), "非章节文件不该算失败");
        assertNull(landing.message(), "非章节文件不该有额外说明");
    }

    @Test
    void 坏YAML不落库且不抛() {
        InterviewScript script = newScript("lander-bad");
        ChapterLander.Landing landing = lander.land(script.getCode(),
                "scripts/lander-bad/Chapters/03.yaml", "events:\n  - 不是对象\n");

        assertFalse(landing.ok());
        assertTrue(landing.message().contains("不是对象"), "要说清哪不对：" + landing.message());
        assertTrue(chapterRepository.findByScriptIdAndCode(script.getId(), "03").isEmpty(),
                "解析失败的章节不该进库");
    }

    @Test
    void 删章节文件会连库里那一章一起删() {
        InterviewScript script = newScript("lander-del");
        String path = "scripts/lander-del/Chapters/04.yaml";
        lander.land(script.getCode(), path, CHAPTER_YAML);
        assertTrue(chapterRepository.findByScriptIdAndCode(script.getId(), "04").isPresent());

        Optional<String> message = lander.deleteChapter(script.getCode(), "04");
        assertTrue(message.isPresent(), "删掉了要回一句");
        assertTrue(chapterRepository.findByScriptIdAndCode(script.getId(), "04").isEmpty(),
                "库里那一章应当被删掉");
    }

    @Test
    void 从写入路径反推章节id() {
        assertEquals("01", ChapterLander.chapterIdOfPath("scripts/foo/Chapters/01.yaml"));
        assertEquals("01", ChapterLander.chapterIdOfPath("scripts/foo/Chapters/01.YML"));
        assertEquals("sub/02", ChapterLander.chapterIdOfPath("scripts/foo/Chapters/sub/02.yaml"));
        assertNull(ChapterLander.chapterIdOfPath(".agent/design.md"), "非章节路径不认");
        assertNull(ChapterLander.chapterIdOfPath("scripts/foo/Chapters/01.txt"), "非 yaml 不认");
    }

    // ───────────────────────── 事实与承接判断 ─────────────────────────

    @Test
    void 章节号归一_第3章与03是同一章() {
        assertEquals("3", ScriptFacts.chapterKey("03"));
        assertEquals("3", ScriptFacts.chapterKey("第 3 章"));
        assertEquals("", ScriptFacts.chapterKey("intro"), "不含数字的不参与比较");
    }

    @Test
    void 点名区间会展开成多章() {
        List<String> ids = ScriptFacts.chapterIdsIn("02-04");
        assertEquals(List.of("02", "03", "04"), ids);
        // 「第 3 章」归一成 3（原版也是去空白去「第/章」），与 03 的相等性由 chapterKey 二次归一保证
        assertEquals(List.of("3"), ScriptFacts.chapterIdsIn("第 3 章"));
        assertEquals("3", ScriptFacts.chapterKey("03"), "3 与 03 归一后相等");
    }

    @Test
    void 还没有任何章节时改YAML会被判为做不了() {
        ScriptFacts facts = ScriptFacts.empty();
        ScriptFacts.Handoff handoff =
                ScriptFacts.reconcile(SkillAgentRouter.TaskKind.REVISE_CHAPTER, facts);

        assertEquals(ScriptFacts.Handoff.Kind.EXPLAIN, handoff.kind(), "没章节就没得改");
        assertFalse(handoff.canAct());
        assertTrue(ScriptFacts.renderHandoff(handoff).contains("本轮不做这件事"));
    }

    @Test
    void 设计稿在但没细节稿时先写正文() {
        // 点名了 01，但细节稿里没有这一章 → 先让编剧写正文
        ScriptFacts facts = new ScriptFacts(false, false, false, false, Boolean.TRUE, false, false, false);
        ScriptFacts.Handoff handoff =
                ScriptFacts.reconcile(SkillAgentRouter.TaskKind.WRITE_CHAPTER, facts);

        assertEquals(ScriptFacts.Handoff.Kind.PREREQUISITE, handoff.kind());
        assertEquals(SkillAgentRouter.TaskKind.DRAFT_CHAPTER, handoff.first(), "前置是「写正文」");
        String text = ScriptFacts.renderHandoff(handoff);
        assertTrue(text.contains("同一轮"), "必须说成同一轮，否则模型做完前置就收尾：" + text);
    }

    @Test
    void 闲聊类任务永远直接做() {
        ScriptFacts.Handoff handoff =
                ScriptFacts.reconcile(SkillAgentRouter.TaskKind.CHAT, ScriptFacts.empty());
        assertEquals(ScriptFacts.Handoff.Kind.PROCEED, handoff.kind());
        assertEquals("", ScriptFacts.renderHandoff(handoff), "直通不该有交接单文字");
    }

    @Test
    void 从沙箱取事实_设计稿能读出章节列表() throws Exception {
        Path dir = Files.createTempDirectory("facts");
        Path agentDir = dir.resolve(".agent");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("design.md"), """
                ## 自我介绍
                id: 01

                ## 项目经历
                id: 02
                """, StandardCharsets.UTF_8);
        Files.writeString(agentDir.resolve("chapter-details.md"), """
                ## 第1章 · 自我介绍
                讲点什么
                """, StandardCharsets.UTF_8);

        ScriptFacts facts = ScriptFacts.collect(dir, "01", List.of());

        assertTrue(facts.hasDesign(), "设计稿存在");
        assertTrue(facts.hasPlan(), "能读出章节列表");
        assertTrue(facts.hasNext(), "还有没落盘的章");
        assertTrue(facts.hasDraft(), "细节稿里有第 1 章");
        assertEquals(Boolean.FALSE, facts.targetExists(), "01 还没落盘，所以不存在");
    }

    @Test
    void 从库取已落盘章节() {
        InterviewScript script = newScript("lander-facts");
        lander.land(script.getCode(), "scripts/lander-facts/Chapters/01.yaml", CHAPTER_YAML);

        List<String> written = lander.writtenChapterIds(script.getCode());
        assertEquals(List.of("01"), written, "库里落了哪章就是哪章");

        // 设计稿说 01、02 两章，01 已落盘 → 下一个待写是 02
        ScriptFacts facts = ScriptFacts.collect(null, "02", written);
        assertTrue(facts.hasWritten(), "已经有落盘章节");
        assertEquals(Boolean.FALSE, facts.targetExists(), "02 还没落盘");
    }
}

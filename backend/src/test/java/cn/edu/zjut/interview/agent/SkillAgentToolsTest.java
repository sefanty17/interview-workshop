package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skill Agent 工具执行层（规格 E7a2 §5.2 的 9 条）。
 *
 * <p>文件类用例全部落在 JUnit 的 {@code @TempDir} 沙箱里，测试结束自动删；
 * 唯一碰库的是 validate_script（{@code interview_script} / {@code interview_chapter}
 * 是既有表），{@code @AfterEach} 按本类建的 id 删干净，不留脏数据。
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillAgentToolsTest {

    @Autowired
    private InterviewScriptRepository scriptRepository;

    @Autowired
    private InterviewChapterRepository chapterRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChapterLander lander;

    @TempDir
    Path sandbox;

    /** 本轮测试建过的剧本 id，{@code @AfterEach} 统一删掉。 */
    private final List<Long> createdScripts = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdScripts) {
            chapterRepository.deleteAll(chapterRepository.findByScriptIdOrderByOrderNumAsc(id));
            scriptRepository.deleteById(id);
        }
        createdScripts.clear();
    }

    // ───────────────────────── 构造辅助 ─────────────────────────

    /** 常规构造：沙箱 + 技能库（沙箱下的 skills/）。 */
    private SkillAgentTools tools() {
        SkillAgentFileTools fileTools = new SkillAgentFileTools(sandbox, false);
        return new SkillAgentTools(fileTools, sandbox.resolve("skills"),
                scriptRepository, chapterRepository, null, lander);
    }

    /** validator 注入版（validate_script 用）。 */
    private SkillAgentTools toolsWithValidator(cn.edu.zjut.interview.editor.ScriptValidator validator) {
        SkillAgentFileTools fileTools = new SkillAgentFileTools(sandbox, false);
        return new SkillAgentTools(fileTools, sandbox.resolve("skills"),
                scriptRepository, chapterRepository, validator, lander);
    }

    private static SkillAgentTools.RunContext runCtx(String boundKey) {
        return new SkillAgentTools.RunContext(boundKey, List.of("bad", "good"));
    }

    private static final SkillAgentTools.ItemScope WRITE_SCOPE =
            new SkillAgentTools.ItemScope(true, SkillAgentRouter.TaskKind.WRITE_CHAPTER);
    private static final SkillAgentTools.ItemScope CHAT_SCOPE =
            new SkillAgentTools.ItemScope(false, SkillAgentRouter.TaskKind.CHAT);

    /**
     * 测试里放行全部工具的白名单。
     *
     * <p>原先取 {@code SkillAgentRouter.ALL_TOOLS}，那个常量已经删了（它含 {@code read_skill}，
     * 而四角色一律不给这个口子）。测试要的是"不受白名单限制"，所以这里显式列全。
     */
    private static final List<String> ALL = List.of(
            "list_skills", "read_skill", "validate_script",
            "list_files", "read_file", "write_file", "edit_file", "delete_file");

    private SkillAgentTools.ToolResult call(SkillAgentTools tools, String name, String argsJson) {
        return tools.execute(runCtx(null), WRITE_SCOPE, ALL, name, readTree(argsJson));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────── 9 条 ─────────────────────────

    // 1. list_skills：空目录返回「没有已安装的技能」；有 2 个技能目录时返回 2 条且字段齐全
    @Test
    void listSkillsEmptyThenTwo() {
        SkillAgentTools tools = tools();
        SkillAgentTools.ToolResult empty = call(tools, "list_skills", "{}");
        assertTrue(empty.ok());
        assertTrue(empty.output().contains("没有已安装的技能"));

        writeSkill("skill-a", "第一个技能");
        writeSkill("skill-b", "第二个技能");
        SkillAgentTools.ToolResult result = call(tools, "list_skills", "{}");
        assertTrue(result.ok());
        assertTrue(result.output().contains("skill-a"));
        assertTrue(result.output().contains("skill-b"));
        assertTrue(result.output().contains("(project)"));
        assertTrue(result.output().contains("第一个技能"));
        assertTrue(result.output().contains("第二个技能"));
    }

    // 2. read_skill：读到内容与落盘一致；不存在的技能 → ok:false 且 error 非空
    @Test
    void readSkillRoundTripAndMissing() {
        writeSkill("my-skill", "读我");
        SkillAgentTools tools = tools();
        SkillAgentTools.ToolResult ok = call(tools, "read_skill", "{\"name\":\"my-skill\"}");
        assertTrue(ok.ok());
        assertTrue(ok.output().contains("Reading: my-skill"));
        assertTrue(ok.output().contains("Skill loaded: my-skill"));

        SkillAgentTools.ToolResult missing = call(tools, "read_skill", "{\"name\":\"nope\"}");
        assertFalse(missing.ok());
        assertTrue(missing.error() != null && !missing.error().isBlank());
    }

    // 3. write_file 后 read_file 能读回同样内容
    @Test
    void writeThenReadBack() {
        SkillAgentTools tools = tools();
        SkillAgentTools.ToolResult write = call(tools, "write_file",
                "{\"path\":\"docs/note.txt\",\"content\":\"第一行\\n第二行\"}");
        assertTrue(write.ok(), write.error());
        SkillAgentTools.ToolResult read = call(tools, "read_file", "{\"path\":\"docs/note.txt\"}");
        assertTrue(read.ok(), read.error());
        assertTrue(read.output().contains("第一行\n第二行"));
    }

    // 4. edit_file 把 old 替换成 new；old 不存在 → ok:false
    @Test
    void editReplaceAndMissingOld() {
        SkillAgentTools tools = tools();
        assertTrue(call(tools, "write_file", "{\"path\":\"a.md\",\"content\":\"hello world\"}").ok());
        SkillAgentTools.ToolResult edit = call(tools, "edit_file",
                "{\"path\":\"a.md\",\"old_string\":\"world\",\"new_string\":\"java\"}");
        assertTrue(edit.ok(), edit.error());
        assertTrue(edit.output().contains("替换 1 处"));
        SkillAgentTools.ToolResult read = call(tools, "read_file", "{\"path\":\"a.md\"}");
        assertTrue(read.output().contains("hello java"));

        SkillAgentTools.ToolResult missing = call(tools, "edit_file",
                "{\"path\":\"a.md\",\"old_string\":\"不存在的原文\",\"new_string\":\"x\"}");
        assertFalse(missing.ok());
        assertTrue(missing.error() != null && !missing.error().isBlank());
    }

    // 5. delete_file 后文件不存在；再删 → ok:false
    @Test
    void deleteThenDeleteAgain() throws Exception {
        SkillAgentTools tools = tools();
        assertTrue(call(tools, "write_file", "{\"path\":\"tmp.txt\",\"content\":\"x\"}").ok());
        SkillAgentTools.ToolResult del = call(tools, "delete_file", "{\"path\":\"tmp.txt\"}");
        assertTrue(del.ok(), del.error());
        assertFalse(Files.exists(sandbox.resolve("tmp.txt")));
        SkillAgentTools.ToolResult again = call(tools, "delete_file", "{\"path\":\"tmp.txt\"}");
        assertFalse(again.ok());
        assertTrue(again.error() != null && !again.error().isBlank());
    }

    // 6. 路径越界：../ 与绝对路径都被拒，且不抛异常
    @Test
    void pathEscapeRefusedWithoutException() {
        SkillAgentTools tools = tools();
        // ../ 逃逸
        SkillAgentTools.ToolResult relative = call(tools, "read_file", "{\"path\":\"../secret.txt\"}");
        assertFalse(relative.ok());
        assertTrue(relative.error().contains("拒绝访问文件沙箱之外的路径"));
        // 绝对路径（沙箱之外）
        String outside = sandbox.resolveSibling("outside-target.txt").toAbsolutePath().toString();
        SkillAgentTools.ToolResult absolute = call(tools, "read_file",
                "{\"path\":\"" + outside.replace("\\", "\\\\") + "\"}");
        assertFalse(absolute.ok());
        assertTrue(absolute.error().contains("拒绝访问文件沙箱之外的路径"));
        // 写工具同样被拒
        SkillAgentTools.ToolResult writeEscape = call(tools, "write_file",
                "{\"path\":\"../evil.txt\",\"content\":\"x\"}");
        assertFalse(writeEscape.ok());
    }

    // 7. validate_script：已知坏剧本返回非空诊断；正常剧本 errorCount 为 0
    @Test
    void validateScriptBadAndGood() {
        cn.edu.zjut.interview.editor.ScriptValidator validator =
                new cn.edu.zjut.interview.editor.ScriptValidator(
                        scriptRepository, objectMapper, "src/test/resources/validator-assets");
        SkillAgentTools tools = toolsWithValidator(validator);

        // 坏剧本：intro 指向不存在的章节 → config.intro_missing（错误）
        InterviewScript bad = insertScript("e7a2-bad", "E7a2坏剧本", "ghost",
                "[{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next\":\"end\"}]");
        SkillAgentTools.ToolResult badReport = tools.execute(runCtx(null), CHAT_SCOPE,
                ALL, "validate_script", readTree("{\"script_key\":\"e7a2-bad\"}"));
        assertTrue(badReport.ok(), badReport.error());
        assertTrue(badReport.output().contains("[校验报告] 剧本：e7a2-bad"));
        assertTrue(badReport.output().contains("错误 1 条"), badReport.output());

        // 正常剧本：intro 存在、章节有 chapter_end → errorCount == 0
        InterviewScript good = insertScript("e7a2-good", "E7a2好剧本", "main",
                "[{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next\":\"end\"}]");
        SkillAgentTools.ToolResult goodReport = tools.execute(runCtx(null), CHAT_SCOPE,
                ALL, "validate_script", readTree("{\"script_key\":\"e7a2-good\"}"));
        assertTrue(goodReport.ok(), goodReport.error());
        assertTrue(goodReport.output().contains("校验通过（error_count = 0）"), goodReport.output());

        assertNotEquals(bad.getId(), good.getId());
    }

    // 8. 未知工具名 → ok:false 且 error 含拒绝措辞
    @Test
    void unknownToolRefused() {
        SkillAgentTools tools = tools();
        SkillAgentTools.ToolResult result = call(tools, "totally_unknown_tool", "{}");
        assertFalse(result.ok());
        assertTrue(result.error().contains("本轮不允许调用"));
        assertTrue(result.error().contains("totally_unknown_tool"));
        // execute_command 显式未启用（规格 §2：本批不做，只留拒绝分支）
        SkillAgentTools.ToolResult command = call(tools, "execute_command", "{\"command\":\"dir\"}");
        assertFalse(command.ok());
        assertTrue(command.error().contains("execute_command 未启用"));
    }

    // ───────────────────────── 辅助 ─────────────────────────

    /** 在沙箱的技能库里写一个技能目录（SKILL.md 带 description frontmatter）。 */
    private void writeSkill(String name, String description) {
        try {
            Path dir = sandbox.resolve("skills").resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"),
                    "---\nname: " + name + "\ndescription: " + description + "\n---\n\n# " + name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 插一个剧本 + 一个章节（intro 由章节 code 承担）；记录 id 供清理。 */
    private InterviewScript insertScript(String code, String scriptName, String introChapter, String events) {
        InterviewScript script = new InterviewScript();
        script.setCode(code);
        script.setScriptName(scriptName);
        script.setIntroChapter(introChapter);
        script.setJobCode("java");
        script.setEnabled(Boolean.TRUE);
        script = scriptRepository.save(script);
        createdScripts.add(script.getId());

        InterviewChapter chapter = new InterviewChapter();
        chapter.setScriptId(script.getId());
        chapter.setCode("main");
        chapter.setName("开场");
        chapter.setOrderNum(1);
        chapter.setEvents(events);
        chapterRepository.save(chapter);
        return script;
    }
}

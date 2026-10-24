package cn.edu.zjut.interview.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务作用域（{@link RoleScope}）的文本契约。
 *
 * <p>对齐 {@code role.rs::directive / scope_note / boundary_note}：这三样是「模型知道这一项必须产出什么」
 * 的依据，缺了就会出现「路由对了、工具也给对了，但模型读完文件反过来问用户、一个字节都不写」。
 * 这里只钉两条：<b>手册真的被读到了</b>（首句与 md 一致）、<b>路径口径已换成本项目的</b>。
 */
class RoleScopeTest {

    /** kind → 对应的 directive 资源（与 {@link RoleScope} 里的映射同口径，用于比对首句）。 */
    private static final Map<SkillAgentRouter.TaskKind, String> DIRECTIVE_RESOURCES = new EnumMap<>(Map.of(
            SkillAgentRouter.TaskKind.WRITE_CHAPTER, "/agent/prompts/role_directive_write_chapter.md",
            SkillAgentRouter.TaskKind.OUTLINE, "/agent/prompts/role_directive_outline.md",
            SkillAgentRouter.TaskKind.DRAFT_CHAPTER, "/agent/prompts/role_directive_draft_chapter.md",
            SkillAgentRouter.TaskKind.REVISE_OUTLINE, "/agent/prompts/role_directive_revise_outline.md",
            SkillAgentRouter.TaskKind.REVISE_CHAPTER, "/agent/prompts/role_directive_revise_chapter.md",
            SkillAgentRouter.TaskKind.POLISH, "/agent/prompts/role_directive_polish.md"));

    @Test
    void directive首句与对应手册一致() {
        for (Map.Entry<SkillAgentRouter.TaskKind, String> entry : DIRECTIVE_RESOURCES.entrySet()) {
            String expected = firstSentence(readClasspath(entry.getValue()));
            String actual = firstSentence(RoleScope.directive(entry.getKey()));
            assertFalse(expected.isBlank(), entry.getValue() + " 首句读不出来");
            assertFalse(actual.isBlank(), entry.getKey().key() + " 的 directive 为空");
            // 首句是「手册真的被读到了」的证据；两边都按本项目口径归一化后应当逐字相等
            //（差异只允许是路径口径，见 RoleScope 的字符串替换）
            assertEquals(projectTerms(expected), projectTerms(actual),
                    entry.getKey().key() + " 的 directive 首句应与手册首句一致");
        }
    }

    @Test
    void 没有手册的任务类型directive为空() {
        for (SkillAgentRouter.TaskKind kind : SkillAgentRouter.TaskKind.values()) {
            if (DIRECTIVE_RESOURCES.containsKey(kind)) {
                assertFalse(RoleScope.directive(kind).isBlank(), kind.key() + " 应该有 directive");
            } else {
                assertTrue(RoleScope.directive(kind).isEmpty(), kind.key() + " 不该有 directive");
            }
        }
    }

    @Test
    void scopeNote写正文带上点名的章号() {
        String note = RoleScope.scopeNote(SkillAgentRouter.TaskKind.DRAFT_CHAPTER, "3");
        assertTrue(note.contains("第 3 章"), "实际=" + note);
        assertFalse(note.contains("{chap}"), "占位符没填： " + note);
        // 原版写的是 Chapters/ 与 story_config.yaml，本项目换成表口径
        assertTrue(note.contains("interview_chapter"), "实际=" + note);
        assertFalse(note.contains("Chapters/"), "路径口径没换： " + note);
        assertFalse(note.contains("story_config.yaml"), "路径口径没换： " + note);
        // 这三条是本项目真在用的路径，必须保持原样
        assertTrue(note.contains(".agent/chapter-details.md"), "实际=" + note);
        assertTrue(note.contains(".agent/design.md"), "实际=" + note);
    }

    @Test
    void scopeNote写正文不带章号时用点名说法() {
        String note = RoleScope.scopeNote(SkillAgentRouter.TaskKind.DRAFT_CHAPTER, "");
        assertTrue(note.contains("点名的这一章"), "实际=" + note);
        assertFalse(note.contains("{chap}"), "占位符没填： " + note);
    }

    @Test
    void scopeNote转YAML带上章号与表口径() {
        String note = RoleScope.scopeNote(SkillAgentRouter.TaskKind.WRITE_CHAPTER, "");
        assertTrue(note.contains("interview_chapter"), "实际=" + note);
        assertFalse(note.contains("Chapters/"), "路径口径没换： " + note);
        assertFalse(note.contains("story_config.yaml"), "路径口径没换： " + note);
        assertTrue(note.contains(".agent/design.md"), "实际=" + note);
        // 留空时 id 占位是 <id>
        assertTrue(note.contains("<id>"), "实际=" + note);

        String withId = RoleScope.scopeNote(SkillAgentRouter.TaskKind.WRITE_CHAPTER, "03");
        assertTrue(withId.contains("code=03"), "实际=" + withId);
        assertFalse(withId.contains("<id>"), "实际=" + withId);
    }

    @Test
    void scopeNote只读轮不许动文件() {
        assertTrue(RoleScope.scopeNote(SkillAgentRouter.TaskKind.CHAT, "").contains("不动任何文件"));
        assertTrue(RoleScope.scopeNote(SkillAgentRouter.TaskKind.COLLECT, "").contains("不动任何文件"));
        assertTrue(RoleScope.scopeNote(SkillAgentRouter.TaskKind.SUMMARIZE, "").contains("不动任何文件"));
        assertTrue(RoleScope.scopeNote(SkillAgentRouter.TaskKind.POLISH, "").contains("不动任何文件"));
    }

    @Test
    void scopeNote九个分支都有内容() {
        for (SkillAgentRouter.TaskKind kind : SkillAgentRouter.TaskKind.values()) {
            assertFalse(RoleScope.scopeNote(kind, "3").isBlank(), kind.key() + " 的 scopeNote 为空");
        }
    }

    @Test
    void boundaryNote只给只读轮() {
        assertNotNull(RoleScope.boundaryNote(SkillAgentRouter.TaskKind.CHAT));
        assertFalse(RoleScope.boundaryNote(SkillAgentRouter.TaskKind.CHAT).isBlank());
        assertNotNull(RoleScope.boundaryNote(SkillAgentRouter.TaskKind.COLLECT));
        assertNotNull(RoleScope.boundaryNote(SkillAgentRouter.TaskKind.SUMMARIZE));
        assertNull(RoleScope.boundaryNote(SkillAgentRouter.TaskKind.WRITE_CHAPTER));
        assertNull(RoleScope.boundaryNote(SkillAgentRouter.TaskKind.POLISH));
        assertNull(RoleScope.boundaryNote(SkillAgentRouter.TaskKind.OUTLINE));
    }

    @Test
    void 任何kind都不抛异常() {
        for (SkillAgentRouter.TaskKind kind : SkillAgentRouter.TaskKind.values()) {
            assertDoesNotThrow(() -> RoleScope.directive(kind));
            assertDoesNotThrow(() -> RoleScope.scopeNote(kind, ""));
            assertDoesNotThrow(() -> RoleScope.scopeNote(kind, null));
            assertDoesNotThrow(() -> RoleScope.boundaryNote(kind));
        }
        assertDoesNotThrow(() -> RoleScope.directive(null));
        assertDoesNotThrow(() -> RoleScope.scopeNote(null, "3"));
        assertDoesNotThrow(() -> RoleScope.boundaryNote(null));
    }

    /** 首句：第一个换行之前的全部内容（md 都是一句话一行）。 */
    private static String firstSentence(String text) {
        int cut = text.indexOf('\n');
        return (cut < 0 ? text : text.substring(0, cut)).strip();
    }

    /** 原版路径口径 → 本项目口径（与 {@link RoleScope} 同口径，只用于比对）。 */
    private static String projectTerms(String text) {
        return text
                .replace("Chapters/{id}.yaml", "interview_chapter 表里 code={id} 的那一行")
                .replace("Chapters/<id>.yaml", "interview_chapter 表里 code=<id> 的那一行")
                .replace("story_config.yaml", "interview_script 表")
                .replace("Chapters/", "interview_chapter 表");
    }

    private static String readClasspath(String resource) {
        try (InputStream in = RoleScopeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "缺资源 " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

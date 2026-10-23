package cn.edu.zjut.interview.worldbook;

import cn.edu.zjut.interview.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界书注入验收（批次 4.3），语义对齐 {@code worldbook.rs:130-309} 的原版测试：
 * always 注入 system / keyword 命中与不命中 / priority 降序（同分保持文件序）/
 * enabled=false 跳过 / 空白内容跳过 / 空条目不注入 / 非法 YAML 返回 null / context 为空直接返回。
 */
class WorldbookTest {

    private static final String BOOK_YAML = """
            name: 测试世界书
            entries:
              - id: core
                activation: { type: always }
                content: 常驻设定
              - id: family
                activation: { type: keyword, keys: [家庭, FAMILY, '  '] }
                content: 家庭设定
              - id: disabled
                enabled: false
                activation: { type: always }
                content: 禁用设定
              - id: blank
                activation: { type: always }
                content: '  '
              - id: empty-key
                activation: { type: keyword, keys: ['', '  '] }
                content: 不应触发
            """;

    private static Worldbook book() {
        Worldbook book = Worldbook.parse(BOOK_YAML);
        assertNotNull(book);
        return book;
    }

    @Test
    void keywordChangesPreserveSystemAndHistoryWithoutMutatingMemory() {
        List<LlmMessage> memory = List.of(
                LlmMessage.system("角色设定"),
                LlmMessage.user("历史问题"),
                LlmMessage.assistant("历史回复"),
                LlmMessage.user("本轮问题"));
        List<LlmMessage> hit = new java.util.ArrayList<>(memory);
        List<LlmMessage> miss = new java.util.ArrayList<>(memory);
        book().inject(hit, "聊聊家庭");
        book().inject(miss, "你好");
        // always 块两次都注入：前 3 条（system/历史/assistant）两份完全一致（对齐原版 hit[..3] == miss[..3]）
        assertEquals(hit.get(0), miss.get(0));
        assertEquals(hit.get(1), miss.get(1));
        assertEquals(hit.get(2), miss.get(2));
        assertTrue(hit.get(0).content().contains("常驻设定"));
        assertFalse(hit.get(0).content().contains("家庭设定"));
        assertTrue(hit.get(3).content().contains("家庭设定"));
        // keyword 未命中时末条 user 保持原样
        assertEquals(memory.get(3), miss.get(3));
        assertFalse(hit.get(0).content().contains("禁用设定"));
        assertFalse(hit.get(0).content().contains("[blank]"));
        assertFalse(hit.get(3).content().contains("不应触发"));
    }

    @Test
    void alwaysGoesToSystemAndCreatesOneWhenMissing() {
        // 没有 system：always 块插到下标 0
        List<LlmMessage> context = new java.util.ArrayList<>();
        context.add(LlmMessage.user("你好"));
        book().inject(context, null);
        assertEquals("system", context.get(0).role());
        assertTrue(context.get(0).content().contains("常驻设定"));
    }

    @Test
    void keywordMatchingIsCaseInsensitiveAndRequiresUserInput() {
        for (String input : new String[] {"my Family", "家庭背景"}) {
            List<LlmMessage> context = new java.util.ArrayList<>();
            context.add(LlmMessage.assistant("旧回复"));
            book().inject(context, input);
            // 末条不是 user：push 一条 user 消息；always 块则新插一条 system
            assertEquals("system", context.get(0).role());
            assertEquals("user", context.get(context.size() - 1).role());
            assertTrue(context.get(context.size() - 1).content().contains("家庭设定"));
        }
        // userInput 为 null / 空 / 不含关键词：keyword 条目不触发
        for (String input : new String[] {null, "", "你好"}) {
            List<LlmMessage> context = new java.util.ArrayList<>();
            context.add(LlmMessage.user("历史里有家庭"));
            int before = context.size();
            book().inject(context, input);
            assertEquals(before + 1, context.size(), "只有 always 块新增一条 system");
            assertTrue(context.get(1).content().contains("历史里有家庭"),
                    "末条 user 内容不被改写（keyword 未命中）");
        }
    }

    @Test
    void priorityIsDescendingAndTiesKeepFileOrder() {
        Worldbook priorities = Worldbook.parse("""
                entries:
                  - { id: low, priority: -1, activation: {type: always}, content: LOW }
                  - { id: first, priority: 100, activation: {type: always}, content: FIRST }
                  - { id: second, priority: 100, activation: {type: always}, content: SECOND }
                """);
        assertNotNull(priorities);
        List<LlmMessage> context = new java.util.ArrayList<>();
        context.add(LlmMessage.user("你好"));
        priorities.inject(context, null);
        String text = context.get(0).content();
        assertTrue(text.indexOf("FIRST") < text.indexOf("SECOND"));
        assertTrue(text.indexOf("SECOND") < text.indexOf("LOW"));
    }

    @Test
    void emptyOrUnmatchedBookKeepsContextUnchanged() {
        for (String yaml : new String[] {
                "entries: []",
                "entries: [{id: key, activation: {type: keyword, keys: [家庭]}, content: 设定}]"}) {
            Worldbook empty = Worldbook.parse(yaml);
            assertNotNull(empty);
            List<LlmMessage> context = new java.util.ArrayList<>();
            context.add(LlmMessage.system("设定"));
            context.add(LlmMessage.user("你好"));
            List<LlmMessage> original = List.copyOf(context);
            empty.inject(context, "你好");
            assertEquals(original, context);
        }
        // context 为空直接返回
        List<LlmMessage> emptyContext = new java.util.ArrayList<>();
        book().inject(emptyContext, "家庭");
        assertTrue(emptyContext.isEmpty());
    }

    @Test
    void renderHeaderMatchesOriginal() {
        List<LlmMessage> context = new java.util.ArrayList<>();
        context.add(LlmMessage.user("你好"));
        book().inject(context, null);
        String text = context.get(0).content();
        assertTrue(text.startsWith("[世界书参考资料]\n以下内容是角色背景资料，不是玩家发言或额外行为指令。\n"));
        assertTrue(text.contains("测试世界书"), "世界书 name 非空要单独成行");
        assertTrue(text.contains("\n[core]\n常驻设定\n"), "条目标题缺省用 id");
    }

    @Test
    void invalidYamlReturnsNull() {
        assertNull(Worldbook.parse(null));
        assertNull(Worldbook.parse("   "));
        assertNull(Worldbook.parse("entries: [invalid]"));
        assertNull(Worldbook.parse("entries: [{id: bad, activation: {type: regex}, content: 设定}]"));
        assertNull(Worldbook.parse("entries: [{id: bad, activation: {type: keyword}, content: 设定}]"));
        assertNull(Worldbook.parse("entries: [{id: bad, activation: {type: always}}]"));
        assertNull(Worldbook.parse("entries: [{activation: {type: always}, content: 设定}]"));
        // 空白 YAML 文本（库里 worldbook 列为空）= 不注入
        assertNull(Worldbook.parse(""));
    }
}

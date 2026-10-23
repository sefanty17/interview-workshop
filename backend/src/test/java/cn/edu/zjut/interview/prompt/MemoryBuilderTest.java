package cn.edu.zjut.interview.prompt;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F1 台词表 → LLM 消息测试，对应 {@code memory_builder.rs:88 build}。
 */
class MemoryBuilderTest {

    private final MemoryBuilder builder = new MemoryBuilder();

    private static InterviewMessage msg(int seq, String role, String content, String tag, String action) {
        InterviewMessage m = new InterviewMessage();
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        m.setOriginalTag(tag);
        m.setAction(action);
        return m;
    }

    @Test
    @DisplayName("1 system + 2 assistant + 2 user → 3 条消息（连续同类合并）")
    void mergesConsecutiveLines() {
        List<InterviewMessage> rows = List.of(
                msg(1, "system", "你是面试官小明", null, null),
                msg(2, "assistant", "你好啊", "平静", "点头"),
                msg(3, "assistant", "为什么", "疑惑", null),
                msg(4, "user", "我来面试", null, null),
                msg(5, "user", "岗位是后端", null, null));

        List<LlmMessage> out = builder.build(rows);

        assertEquals(3, out.size());
        assertEquals("system", out.get(0).role());
        assertEquals("你是面试官小明", out.get(0).content());

        assertEquals("assistant", out.get(1).role());
        // 每句：【情绪】正文\n(动作)\n\n；整段末再补一个 \n
        assertEquals("【平静】你好啊\n(点头)\n\n【疑惑】为什么\n\n\n", out.get(1).content());

        assertEquals("user", out.get(2).role());
        // 多条 user 台词用 \n 连接，末尾不加换行
        assertEquals("我来面试\n岗位是后端", out.get(2).content());
    }

    @Test
    @DisplayName("两条 system → 只取第一条")
    void keepsOnlyFirstSystem() {
        List<InterviewMessage> rows = List.of(
                msg(1, "system", "第一条人设", null, null),
                msg(2, "system", "第二条人设", null, null),
                msg(3, "user", "开始吧", null, null));

        List<LlmMessage> out = builder.build(rows);

        assertEquals(2, out.size());
        assertEquals("system", out.get(0).role());
        assertEquals("第一条人设", out.get(0).content());
        assertEquals("user", out.get(1).role());
        assertEquals("开始吧", out.get(1).content());
    }

    @Test
    @DisplayName("按 seq 升序还原（入参乱序也一样）")
    void sortsBySeq() {
        List<InterviewMessage> rows = List.of(
                msg(3, "user", "第二轮回答", null, null),
                msg(1, "system", "人设", null, null),
                msg(2, "assistant", "第一个问题", "认真", null));

        List<LlmMessage> out = builder.build(rows);

        assertEquals(3, out.size());
        assertEquals("system", out.get(0).role());
        assertEquals("assistant", out.get(1).role());
        assertEquals("【认真】第一个问题\n\n\n", out.get(1).content());
        assertEquals("user", out.get(2).role());
        assertEquals("第二轮回答", out.get(2).content());
    }
}

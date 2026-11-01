package cn.edu.zjut.interview.summary;

import cn.edu.zjut.interview.dto.SummaryResult.Coverage;
import cn.edu.zjut.interview.dto.SummaryResult.QuestionNote;
import cn.edu.zjut.interview.dto.SummaryResult.Vocabulary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 总结输出解析的单测（issue #4）：只测纯解析，不碰数据库与网络。
 */
class SummaryParserTest {

    @Test
    @DisplayName("解析完整摘要：围栏被剥掉，逐轮/覆盖/用词都还原")
    void parseFull() {
        String raw = """
                ```json
                {
                  "overview": "聊了 JVM 内存与并发，回答整体以背诵为主",
                  "questions": [
                    {"index": 1, "question": "讲讲 JVM 内存分区", "points": ["堆与栈的区别"],
                     "blockers": ["没说元空间"], "depth": "中"},
                    {"question": "synchronized 的锁升级过程", "points": [], "blockers": ["只说了重量级锁"], "depth": "较深"}
                  ],
                  "coverage": {"covered": ["JVM 内存"], "missing": ["并发包"]},
                  "vocabulary": {"good": ["新生代"], "improve": ["把「GC 很慢」换成「停顿时间」"]}
                }
                ```""";

        SummaryParser.SummaryDraft draft = SummaryParser.parse(raw);

        assertEquals("聊了 JVM 内存与并发，回答整体以背诵为主", draft.overview());
        assertEquals(2, draft.questions().size());
        QuestionNote first = draft.questions().get(0);
        assertEquals(1, first.index());
        assertEquals("讲讲 JVM 内存分区", first.question());
        assertEquals(List.of("堆与栈的区别"), first.points());
        assertEquals(List.of("没说元空间"), first.blockers());
        assertEquals("中", first.depth());
        // 题号缺失时按顺序补号；「较深」归一成「深」
        assertEquals(2, draft.questions().get(1).index());
        assertEquals("深", draft.questions().get(1).depth());
        assertEquals(new Coverage(List.of("JVM 内存"), List.of("并发包")), draft.coverage());
        assertEquals(new Vocabulary(List.of("新生代"), List.of("把「GC 很慢」换成「停顿时间」")), draft.vocabulary());
    }

    @Test
    @DisplayName("缺少可选字段：降级成空列表而不是整份失败")
    void parseMissingOptional() {
        SummaryParser.SummaryDraft draft = SummaryParser.parse("{\"overview\":\"只聊了一题\"}");

        assertEquals("只聊了一题", draft.overview());
        assertTrue(draft.questions().isEmpty());
        assertTrue(draft.coverage().covered().isEmpty());
        assertTrue(draft.coverage().missing().isEmpty());
        assertTrue(draft.vocabulary().good().isEmpty());
        assertTrue(draft.vocabulary().improve().isEmpty());
    }

    @Test
    @DisplayName("不是 JSON：报 SUMMARY_BAD_JSON")
    void parseBadJson() {
        SummaryException e = assertThrows(SummaryException.class,
                () -> SummaryParser.parse("抱歉，我无法总结这段对话。"));

        assertEquals(SummaryException.BAD_JSON, e.code());
    }
}

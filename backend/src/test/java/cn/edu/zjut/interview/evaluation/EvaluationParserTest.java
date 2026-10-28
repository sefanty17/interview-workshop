package cn.edu.zjut.interview.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E1 §5.1 的 8 条解析断言：不依赖外网，全部纯字符串 → 对象的单测。
 *
 * <p>用例的分值都挑了能口算的（全 80 → 80、只有 technical=100 → 30），
 * 断言写的是算术结果而不是「跑一遍把结果抄进来」。
 */
class EvaluationParserTest {

    /** 1. 标准 JSON：四个维度分与总分都正确（全 80 × 权重和 1.0 = 80）。 */
    @Test
    void 标准json解析出四个维度与总分() {
        String raw = """
                {
                  "summary": "基础扎实，表达清楚",
                  "strengths": ["原理讲得清楚", "有实战例子"],
                  "weaknesses": ["深度不够"],
                  "suggestions": ["补充源码层面"],
                  "dimensions": {
                    "technical": {"score": 80, "comment": "技术点准确"},
                    "depth":     {"score": 80, "comment": "讲到了原理"},
                    "logic":     {"score": 80, "comment": "层次分明"},
                    "fit":       {"score": 80, "comment": "贴合岗位"}
                  }
                }""";

        EvaluationParser.EvaluationDraft draft = EvaluationParser.parse(raw);

        assertEquals(80, draft.overall());
        assertEquals("基础扎实，表达清楚", draft.summary());
        assertEquals(List.of("原理讲得清楚", "有实战例子"), draft.strengths());
        assertEquals(List.of("深度不够"), draft.weaknesses());
        assertEquals(List.of("补充源码层面"), draft.suggestions());
        assertEquals(4, draft.dimensions().size());
        assertEquals(80, draft.dimensions().get("technical").score());
        assertEquals("贴合岗位", draft.dimensions().get("fit").comment());
    }

    /** 2. 权重生效：只有 technical=100，其余 0 → 总分 = 100 × 0.30 = 30。 */
    @Test
    void 总分按权重加权而非算术平均() {
        String raw = """
                {"dimensions": {
                   "technical": {"score": 100, "comment": "全对"},
                   "depth":     {"score": 0,   "comment": "没讲原理"},
                   "logic":     {"score": 0,   "comment": "跳来跳去"},
                   "fit":       {"score": 0,   "comment": "偏题"}
                }}""";

        EvaluationParser.EvaluationDraft draft = EvaluationParser.parse(raw);

        assertEquals(30, draft.overall(), "100×0.30 + 0×0.25 + 0×0.25 + 0×0.20 = 30");
    }

    /** 3. 带 ```json 围栏：能剥掉并解析。 */
    @Test
    void 带markdown围栏时能解析() {
        String raw = "```json\n" + """
                {"summary":"还行","dimensions": {
                   "technical": {"score": 70, "comment": "a"},
                   "depth":     {"score": 70, "comment": "b"},
                   "logic":     {"score": 70, "comment": "c"},
                   "fit":       {"score": 70, "comment": "d"}
                }}""" + "\n```";

        EvaluationParser.EvaluationDraft draft = EvaluationParser.parse(raw);

        assertEquals("还行", draft.summary());
        assertEquals(70, draft.overall());
    }

    /** 4. 前后有废话（模型最爱的开场白）：按第一个 { 到最后一个 } 截取。 */
    @Test
    void 前后有废话时能解析() {
        String raw = "好的，这是评估结果：\n" + """
                {"summary":"中规中矩","dimensions": {
                   "technical": {"score": 60, "comment": "a"},
                   "depth":     {"score": 60, "comment": "b"},
                   "logic":     {"score": 60, "comment": "c"},
                   "fit":       {"score": 60, "comment": "d"}
                }}""" + "\n希望对你有帮助。";

        EvaluationParser.EvaluationDraft draft = EvaluationParser.parse(raw);

        assertEquals("中规中矩", draft.summary());
        assertEquals(60, draft.overall());
    }

    /** 5. 缺 logic → EVAL_MISSING_DIMENSION，且消息里点名缺哪个。 */
    @Test
    void 缺维度时报missingDimension并点名() {
        String raw = """
                {"dimensions": {
                   "technical": {"score": 80, "comment": "a"},
                   "depth":     {"score": 80, "comment": "b"},
                   "fit":       {"score": 80, "comment": "d"}
                }}""";

        EvaluationException e = assertThrows(EvaluationException.class, () -> EvaluationParser.parse(raw));

        assertEquals(EvaluationException.MISSING_DIMENSION, e.code());
        assertTrue(e.getMessage().contains("logic"), "报错要点名缺哪个维度：" + e.getMessage());
    }

    /** 6. score=150：clamp 成 100，不报错（该维度权重 0.30 → 总分 30 + 其余 0）。 */
    @Test
    void 分数越界时clamp而不报错() {
        String raw = """
                {"dimensions": {
                   "technical": {"score": 150, "comment": "模型给高了"},
                   "depth":     {"score": 0, "comment": "b"},
                   "logic":     {"score": 0, "comment": "c"},
                   "fit":       {"score": 0, "comment": "d"}
                }}""";

        EvaluationParser.EvaluationDraft draft = EvaluationParser.parse(raw);

        assertEquals(100, draft.dimensions().get("technical").score(), "150 必须 clamp 到 100");
        assertEquals(30, draft.overall());
    }

    /** 7. 完全不是 JSON → EVAL_BAD_JSON。 */
    @Test
    void 不是json时报badJson() {
        EvaluationException e = assertThrows(EvaluationException.class,
                () -> EvaluationParser.parse("抱歉，我无法完成这个请求。"));

        assertEquals(EvaluationException.BAD_JSON, e.code());
    }

    /** 8. strengths 写成字符串而不是数组：降级为空数组，其余字段照常解析。 */
    @Test
    void 要点字段不是数组时降级为空而不作废整份评估() {
        String raw = """
                {"summary":"总结","strengths":"讲得不错","dimensions": {
                   "technical": {"score": 80, "comment": "a"},
                   "depth":     {"score": 80, "comment": "b"},
                   "logic":     {"score": 80, "comment": "c"},
                   "fit":       {"score": 80, "comment": "d"}
                }}""";

        EvaluationParser.EvaluationDraft draft = EvaluationParser.parse(raw);

        assertTrue(draft.strengths().isEmpty(), "字符串不是数组，降级为空数组");
        assertEquals("总结", draft.summary());
        assertEquals(80, draft.overall(), "锦上添花的字段坏了不该影响总分");
    }
}

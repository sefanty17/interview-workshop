package cn.edu.zjut.interview.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.edu.zjut.interview.prompt.PromptConstants.DEFAULT_EXAMPLE_CN;
import static cn.edu.zjut.interview.prompt.PromptConstants.DIALOG_FORMAT_PROMPT_2_BODY;
import static cn.edu.zjut.interview.prompt.PromptConstants.DIALOG_FORMAT_PROMPT_CN;
import static cn.edu.zjut.interview.prompt.PromptConstants.EMOTION_LIMIT_HEAD;
import static cn.edu.zjut.interview.prompt.PromptConstants.EXAMPLE_CUSTOM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 提示词拼装测试。常量长度断言里的数字是用 python 按 indoc 语义
 * （去掉开头换行 + 剥掉公共缩进）从 prompt.rs 原件上量出来的。
 */
class PromptAssemblerTest {

    @Test
    @DisplayName("5 个常量 + 框架句的字符数与 Rust 实测一致")
    void constantsMatchRustLengths() {
        assertEquals(834, DIALOG_FORMAT_PROMPT_CN.length(), "DIALOG_FORMAT_PROMPT_CN (prompt.rs:33-57)");
        assertEquals(155, EMOTION_LIMIT_HEAD.length(), "DIALOG_FORMAT_PROMPT_2_EMOTION_LIMIT_HEAD (prompt.rs:88-92)");
        assertEquals(752, DIALOG_FORMAT_PROMPT_2_BODY.length(), "DIALOG_FORMAT_PROMPT_2_BODY (prompt.rs:93-110)");
        assertEquals(390, DEFAULT_EXAMPLE_CN.length(), "DEFAULT_EXAMPLE_CN (prompt.rs:111-129)");
        assertEquals(31, EXAMPLE_CUSTOM.length(), "EXAMPLE_CUSTOM (prompt.rs:159-162)");
        // 框架句是 build_framing_prefix_cn 生成的，按 u/c 实际长度计算：605
        assertEquals(605, PromptAssembler.buildFramingPrefixCn("小明", "面试官").length(),
                "build_framing_prefix_cn (prompt.rs:163-180)");
        // 首尾换行也要对上：3 个常量带前导 \n，2 个常量以空行结尾
        assertEquals('\n', DIALOG_FORMAT_PROMPT_CN.charAt(0));
        assertEquals("\n\n", DIALOG_FORMAT_PROMPT_2_BODY.substring(DIALOG_FORMAT_PROMPT_2_BODY.length() - 2));
        assertEquals("\n\n", DEFAULT_EXAMPLE_CN.substring(DEFAULT_EXAMPLE_CN.length() - 2));
        assertFalse(EMOTION_LIMIT_HEAD.startsWith("\n"));
        assertTrue(EXAMPLE_CUSTOM.endsWith("：\n"));
    }

    @Test
    @DisplayName("拼装顺序：人设 → 编排段 → 框架句 → 格式 → 示例 → 情绪 → 正文")
    void assembleOrder() {
        String aiPrompt = "你是面试官";
        InterviewPlan plan = new InterviewPlan(
                "后端", "小明", "技术专家面",
                List.of(new InterviewPlan.Stage("开场", "自我介绍"),
                        new InterviewPlan.Stage("深挖", "追问项目")),
                "每章两轮", "问完所有章节");
        String example = "【平静】示例台词";

        String out = PromptAssembler.build(aiPrompt, "求职者", "小明", example, plan.toSegment());

        String[] markers = {
                aiPrompt,
                "【本次面试编排】",
                "以下是我的对话格式提示",       // framing
                "以下是你的对话格式要求",       // DIALOG_FORMAT_PROMPT_CN
                "以下是你正确的输出示范",       // DEFAULT_EXAMPLE_CN
                "以下是更多符合你性格的示范",   // EXAMPLE_CUSTOM
                example,
                "你能从",                       // EMOTION_LIMIT_HEAD
                "你的每一次回复不要只有"        // DIALOG_FORMAT_PROMPT_2_BODY
        };
        int previous = -1;
        for (String marker : markers) {
            int at = out.indexOf(marker);
            assertTrue(at > previous, "段顺序错位: " + marker + " @" + at + " <= " + previous);
            previous = at;
        }
        assertEquals(0, out.indexOf(aiPrompt));
        assertTrue(out.endsWith(DIALOG_FORMAT_PROMPT_2_BODY));
        // 人设与框架句之间正好隔一个空行
        assertTrue(out.contains(aiPrompt + "\n" + plan.toSegment()));
    }

    @Test
    @DisplayName("面试编排段模板逐字")
    void interviewPlanSegment() {
        InterviewPlan plan = new InterviewPlan(
                "后端", "小明", "技术专家面",
                List.of(new InterviewPlan.Stage("开场", "自我介绍"),
                        new InterviewPlan.Stage("深挖", "追问项目")),
                "每章两轮", "问完所有章节");
        assertEquals("""
                【本次面试编排】
                岗位：后端
                面试官：小明（技术专家面）
                流程：
                1. 开场 —— 自我介绍
                2. 深挖 —— 追问项目
                轮次控制：每章两轮
                结束条件：问完所有章节
                """, plan.toSegment());
    }

    @Test
    @DisplayName("短路：人设含「日语翻译」→ 原样返回")
    void shortCircuitJapanese() {
        String aiPrompt = "你是助手，请输出日语翻译。";
        assertEquals(aiPrompt, PromptAssembler.build(aiPrompt, "用户", "小明", "示例"));
        assertEquals(aiPrompt, PromptAssembler.build(aiPrompt, "用户", "小明", "示例", "【本次面试编排】"));
    }

    @Test
    @DisplayName("短路：人设含「以下是我的对话格式提示」→ 原样返回")
    void shortCircuitLegacyFormat() {
        String aiPrompt = "旧版人设\n以下是我的对话格式提示：\n随便写";
        String out = PromptAssembler.build(aiPrompt, "用户", "小明", "示例");
        assertEquals(aiPrompt, out);
        assertFalse(out.contains(DIALOG_FORMAT_PROMPT_CN));
    }

    @Test
    @DisplayName("示例为空串/空 → 不拼 EXAMPLE_CUSTOM（对应 Rust filter(!s.is_empty())）")
    void emptyExampleSkipped() {
        String outEmpty = PromptAssembler.build("人设", "用户", "小明", "");
        String outNull = PromptAssembler.build("人设", "用户", "小明", null);
        assertEquals(outEmpty, outNull);
        assertFalse(outEmpty.contains(EXAMPLE_CUSTOM));
        // 没有示例时，默认示例后面直接接情绪限制
        assertTrue(outEmpty.contains(DEFAULT_EXAMPLE_CN + EMOTION_LIMIT_HEAD));
    }
}

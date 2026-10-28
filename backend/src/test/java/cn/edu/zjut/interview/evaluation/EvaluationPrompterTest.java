package cn.edu.zjut.interview.evaluation;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E1 §5.2 的 4 条 prompt 断言：不依赖外网，只看拼出来的两条消息长什么样。
 *
 * <p>为什么值得单测：system 里少一句「不要围栏」、user 里漏排 system 行，
 * 都是「跑起来能出结果、但结果明显变差」的那类问题，只有断言能挡住。
 */
class EvaluationPrompterTest {

    /** 1. system 里四个维度的中文名都要出现（模型按中文口径打分）。 */
    @Test
    void system里出现四个维度的中文名() {
        String system = build().get(0).content();

        assertTrue(system.contains("技术正确性"), system);
        assertTrue(system.contains("知识深度"), system);
        assertTrue(system.contains("逻辑严谨性"), system);
        assertTrue(system.contains("岗位匹配度"), system);
    }

    /** 2. 「不要 Markdown 围栏」那句护栏必须在 system 里——防模型加围栏，自己不能先丢。 */
    @Test
    void system里有禁止围栏与解释文字的护栏() {
        String system = build().get(0).content();

        assertTrue(system.contains("不要 Markdown 围栏"), system);
        assertTrue(system.contains("只输出 JSON"), system);
        // 评分区间锚点与 JSON 模板：没有锚点模型会把 75 当满分，没有模板它会自由发挥键名
        assertTrue(system.contains(">=85 优秀"), system);
        assertTrue(system.contains("\"dimensions\""), system);
    }

    /** 3. user 里有岗位名与「求职者：」标记，且不夹带 system 提示词的内容。 */
    @Test
    void user里有岗位名与说话人标记且不夹带system提示词() {
        List<InterviewMessage> messages = List.of(
                message(1, InterviewMessage.ROLE_SYSTEM, "你是面试官，请按剧本提问"),
                message(2, InterviewMessage.ROLE_ASSISTANT, "说说 JVM 内存模型"),
                message(3, InterviewMessage.ROLE_USER, "堆和栈，方法区在元空间"));

        List<LlmMessage> built = EvaluationPrompter.build("java", messages);

        assertEquals(2, built.size());
        String user = built.get(1).content();
        assertTrue(user.contains("java"), "user 里要有岗位名：" + user);
        assertTrue(user.contains("求职者："), user);
        assertTrue(user.contains("面试官："), user);
        assertFalse(user.contains("你是面试官，请按剧本提问"), "system 台词必须被跳过：" + user);
        assertFalse(user.contains("你是资深技术面试官与人才评估专家"), "system 提示词不能漏进 user：" + user);
    }

    /** 4. 超长对话（200 条）→ 拼出来的 user 文本 ≤ 8000 字，且保留最后一条。 */
    @Test
    void 超长对话按预算截断且保留最近内容() {
        List<InterviewMessage> messages = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String role = i % 2 == 0 ? InterviewMessage.ROLE_ASSISTANT : InterviewMessage.ROLE_USER;
            messages.add(message(i + 1, role, "第 " + i + " 轮的回答内容，".repeat(4)));
        }
        messages.add(message(201, InterviewMessage.ROLE_USER, "MARK-最后一条回答"));

        String user = EvaluationPrompter.build("java", messages).get(1).content();

        assertTrue(user.length() <= 8000, "user 文本超预算：" + user.length());
        assertTrue(user.contains("MARK-最后一条回答"), "结尾表现最关键，不能先被截掉");
        assertFalse(user.contains("第 0 轮"), "最早的对话应当被丢掉");
    }

    private static List<LlmMessage> build() {
        List<InterviewMessage> messages = List.of(
                message(1, InterviewMessage.ROLE_ASSISTANT, "说说 JVM 内存模型"),
                message(2, InterviewMessage.ROLE_USER, "堆和栈"),
                message(3, InterviewMessage.ROLE_USER, "还有元空间"));
        return EvaluationPrompter.build("java", messages);
    }

    private static InterviewMessage message(int seq, String role, String content) {
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(1L);
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}

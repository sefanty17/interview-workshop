package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.repository.AgentConversationRepository;
import cn.edu.zjut.interview.repository.AgentMessageRepository;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.UserSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3：路由输入四段（规格 {@code .workbuddy/plans/P0-3规格-路由输入.md} 验收第 3、4 条）。
 *
 * <p>只测拼串，不碰 LLM：router 用全 null 依赖构造，{@code buildRouterUser} 不依赖它们。
 */
class SkillAgentRouterInputTest {

    private static final SkillAgentRouter ROUTER =
            new SkillAgentRouter(null, null, new ObjectMapper());

    private static SkillAgentRouter.RouteInput input(String scriptKey, List<String> plan,
                                                     List<String> written, String queue) {
        return new SkillAgentRouter.RouteInput("写第一章", "", null, scriptKey, plan, written, queue);
    }

    @Test
    void unboundScriptSaysNotBound() {
        String text = ROUTER.buildRouterUser(input(null, List.of(), List.of(), ""));
        assertTrue(text.contains("剧本：还没绑定（用户可能想新建一个）"), text);
        assertTrue(text.contains("设计稿：无（或没列出章节）"), text);
        assertTrue(text.contains("已落盘章节：（无）"), text);
    }

    @Test
    void planAndWrittenChaptersAreListed() {
        String text = ROUTER.buildRouterUser(input("demo", List.of("01", "02", "03"), List.of("01"), ""));
        assertTrue(text.contains("剧本：demo"), text);
        assertTrue(text.contains("设计稿已列出章节：01 02 03"), text);
        assertTrue(text.contains("已落盘章节：01"), text);
    }

    @Test
    void emptyQueueOmitsQueueSection() {
        String text = ROUTER.buildRouterUser(input("demo", List.of("01"), List.of(), "  "));
        assertFalse(text.contains("上一轮排过"), text);
    }

    @Test
    void nonEmptyQueueShowsSectionWithConstraint() {
        String text = ROUTER.buildRouterUser(
                input("demo", List.of("01", "02"), List.of(), "1. 写正文 01\n2. 转成 YAML 01"));
        assertTrue(text.contains("【上一轮排过、还没做完的（**只是状态，不是本轮的活**）】"), text);
        assertTrue(text.contains("除非用户这一句是在让你接着做（「继续」「那把它弄完」「剩下的做完」），"), text);
        assertTrue(text.contains("否则**不要**把它们排进 `tasks` —— 他这一句说的是什么，就排什么。"), text);
        assertTrue(text.contains("1. 写正文 01\n2. 转成 YAML 01"), text);
    }

    /**
     * 本项任务书逐字对齐原版：只做占位符替换，不追加任何一句自造的话。
     *
     * <p>（曾经在这里给非对话类追加过一条「本项到此结束…不许反问」的尾巴，与「原版流程不增不减」
     * 冲突，已删除；这里反过来钉住"没有尾巴"。）
     */
    @Test
    void coreRunChatIsVerbatimTemplateSubstitution() {
        String draft = render(SkillAgentRouter.TaskKind.DRAFT_CHAPTER);
        assertEquals("这一项=写正文：01 第 1/1 项 对象=「01」", draft);
        assertFalse(draft.contains("不要问用户"), "不再追加自造收尾：" + draft);
        for (SkillAgentRouter.TaskKind kind : SkillAgentRouter.TaskKind.values()) {
            assertFalse(render(kind).contains("不要问用户"), kind + " 都不追加自造收尾");
        }
    }

    /** 用真 Service 的 {@code renderCoreRunChat}（依赖全 null，这个方法只用模板）。 */
    private static String render(SkillAgentRouter.TaskKind kind) {
        SkillAgentService service = new SkillAgentService(
                (AgentConversationRepository) null, (AgentMessageRepository) null,
                (UserSettingRepository) null, (InterviewScriptRepository) null,
                (InterviewChapterRepository) null, (ConfigService) null, new ObjectMapper(),
                null, (SkillAgentLlmClient) null, (ChapterLander) null,
                (cn.edu.zjut.interview.knowledge.RetrievalService) null, ".");
        return service.renderCoreRunChat(kind, "01", 0, 1, "这一项={} 第 {}/{} 项 对象=「{}」");
    }

    /** {@link SkillAgentRouter#squash} 与 {@code router.rs::squash} 同口径（仅供下文使用）。 */
    @Test
    void squashFlattensAndTruncates() {
        assertEquals("a b", SkillAgentRouter.squash("a\nb"));
        String longText = "字".repeat(121);
        assertEquals("字".repeat(120) + "…", SkillAgentRouter.squash(longText));
    }
}

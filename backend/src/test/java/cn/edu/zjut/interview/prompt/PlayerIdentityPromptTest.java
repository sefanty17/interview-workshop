package cn.edu.zjut.interview.prompt;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家身份解耦：历史渲染与身份/关系块的注入。
 *
 * <p>两件事分开验：
 * <ul>
 *   <li>{@link MemoryBuilder}：玩家行「一律带名」，且老数据 / 旁白行不许被冠名；</li>
 *   <li>{@link PlayerIdentityPrompt}：关系三级优先、两块注入的措辞与边界。</li>
 * </ul>
 */
class PlayerIdentityPromptTest {

    private final MemoryBuilder memoryBuilder = new MemoryBuilder();
    private final PlayerIdentityPrompt prompt = new PlayerIdentityPrompt(new ObjectMapper());

    // ───────────────────────── 历史渲染 ─────────────────────────

    private static InterviewMessage player(int seq, String content, String name) {
        InterviewMessage m = new InterviewMessage();
        m.setSeq(seq);
        m.setRole(InterviewMessage.ROLE_USER);
        m.setContent(content);
        m.setSenderRoleId(InterviewMessage.PLAYER_ROLE_ID);
        m.setDisplayName(name);
        m.setIdentityId("me");
        return m;
    }

    private static InterviewMessage narrator(int seq, String content) {
        InterviewMessage m = new InterviewMessage();
        m.setSeq(seq);
        m.setRole(InterviewMessage.ROLE_USER);
        m.setContent(content);
        // 引擎借 role=user 落旁白，sender_role_id 为 null
        m.setSenderRoleId(null);
        m.setMsgType("narration");
        return m;
    }

    @Test
    void 玩家台词渲染成带名字的一行() {
        List<LlmMessage> messages = memoryBuilder.build(List.of(player(1, "我会 Java。", "候选人")));

        assertEquals(1, messages.size());
        assertEquals("候选人: 我会 Java。", messages.get(0).content());
    }

    @Test
    void 旁白行不带名字() {
        List<LlmMessage> messages = memoryBuilder.build(List.of(narrator(1, "面试开始了")));

        assertEquals("面试开始了", messages.get(0).content(), "旁白不该被冠名");
    }

    @Test
    void 老数据没填名字时保持裸内容() {
        // 本批之前的库里没有 display_name 列，历史数据是 null
        List<LlmMessage> messages = memoryBuilder.build(List.of(player(1, "我会 Java。", null)));

        assertEquals("我会 Java。", messages.get(0).content(), "没名字就保持原样，不能凭空造一个");
    }

    @Test
    void 名字是旁白或系统时不冠名() {
        List<LlmMessage> a = memoryBuilder.build(List.of(player(1, "天亮了", "旁白")));
        List<LlmMessage> b = memoryBuilder.build(List.of(player(1, "时间 12:00", "系统")));

        assertEquals("天亮了", a.get(0).content());
        assertEquals("时间 12:00", b.get(0).content());
    }

    @Test
    void 多个身份的台词各自带各自的名字() {
        List<LlmMessage> messages = memoryBuilder.build(List.of(
                player(1, "我是候选人。", "候选人"),
                player(2, "我是邻座面试官。", "面试官乙")));

        assertEquals(1, messages.size(), "连续 user 行合成一条");
        assertEquals("候选人: 我是候选人。\n面试官乙: 我是邻座面试官。", messages.get(0).content(),
                "换身份后两句话必须分得清是谁说的");
    }

    // ───────────────────────── 关系三级优先 ─────────────────────────

    @Test
    void 关系先取说话者自己的写法() {
        var speaker = new PlayerIdentityPrompt.AiEndpoint("风雪");
        var target = new PlayerIdentityPrompt.MeEndpoint("me");

        var view = prompt.resolveRelation(speaker, target,
                Map.of("me:me", "我是他的面试官"),
                Map.of("ai:风雪", "他是我以前的同事"),
                "公开设定");

        assertTrue(view.isPresent());
        assertEquals("我是他的面试官", view.get().text(), "说话者卡优先");
        assertEquals(PlayerIdentityPrompt.RelationSource.SPEAKER, view.get().source());
    }

    @Test
    void 说话者没写才取对方的写法() {
        var speaker = new PlayerIdentityPrompt.AiEndpoint("风雪");
        var target = new PlayerIdentityPrompt.MeEndpoint("me");

        var view = prompt.resolveRelation(speaker, target, Map.of(),
                Map.of("ai:风雪", "他是我以前的同事"), "公开设定");

        assertEquals("他是我以前的同事", view.get().text());
        assertEquals(PlayerIdentityPrompt.RelationSource.COUNTERPARTY, view.get().source());
    }

    @Test
    void 两边都没写才用提示词兜底() {
        var speaker = new PlayerIdentityPrompt.AiEndpoint("风雪");
        var target = new PlayerIdentityPrompt.MeEndpoint("me");

        var view = prompt.resolveRelation(speaker, target, Map.of(), Map.of(), "公开设定");

        assertEquals("公开设定", view.get().text());
        assertEquals(PlayerIdentityPrompt.RelationSource.PROMPT_FALLBACK, view.get().source());
    }

    @Test
    void 全空时没有关系() {
        var speaker = new PlayerIdentityPrompt.AiEndpoint("风雪");
        var target = new PlayerIdentityPrompt.MeEndpoint("me");
        assertTrue(prompt.resolveRelation(speaker, target, Map.of(), Map.of(), "  ").isEmpty());
    }

    // ───────────────────────── 两个注入块 ─────────────────────────

    @Test
    void 玩家块声明优先级并在前三项全空时返回空串() {
        assertEquals("", prompt.buildPlayerBlock(null, null, null), "全空不能塞空壳标题");

        String block = prompt.buildPlayerBlock("候选人", "浙工大软工大三", null);
        assertTrue(block.contains("【本局必须遵守：正在与你对话的「我」】"));
        assertTrue(block.contains("本节优先于上面人设"), "必须声明优先级");
        assertTrue(block.contains("名字：候选人"));
        assertTrue(block.contains("所有角色都看得到的设定"));
    }

    @Test
    void 只有说话者自己写的关系才当成态度注入() {
        String fromSpeaker = prompt.buildPlayerBlock("候选人", null,
                new PlayerIdentityPrompt.RelationView("我是他的面试官",
                        PlayerIdentityPrompt.RelationSource.SPEAKER));
        assertTrue(fromSpeaker.contains("你和我的关系"), "说话者写的才是我和你的关系");
        assertTrue(fromSpeaker.contains("不要只是知道"));

        String fromCounterparty = prompt.buildPlayerBlock("候选人", null,
                new PlayerIdentityPrompt.RelationView("他是我以前的同事",
                        PlayerIdentityPrompt.RelationSource.COUNTERPARTY));
        assertFalse(fromCounterparty.contains("你和我的关系"), "对方的说法不能当成说话者的态度");
    }

    @Test
    void 其他角色块不做提示词兜底() {
        var speaker = new PlayerIdentityPrompt.AiEndpoint("风雪");
        // 邻座没有任何显式关系 → 尽管它有自己的提示词，也不该被编成"我对 ta 的看法"
        var peer = new PlayerIdentityPrompt.ScenePeer("松子", "松子", Map.of());

        assertEquals("", prompt.buildPeersBlock(speaker, Map.of(), List.of(peer)),
                "没有显式关系就不注入，否则等于凭空编关系");
    }

    @Test
    void 其他角色块区分两种关系来源() {
        var speaker = new PlayerIdentityPrompt.AiEndpoint("风雪");
        var peer = new PlayerIdentityPrompt.ScenePeer("松子", "松子", Map.of("ai:风雪", "他爱抢话"));

        String block = prompt.buildPeersBlock(speaker, Map.of(), List.of(peer));
        assertTrue(block.contains("【你眼里的其他角色】"));
        assertTrue(block.contains("ta 对你的说法（是 ta 的说法，未必是事实）：他爱抢话"));
    }

    @Test
    void relations解析坏JSON时按无关系处理() {
        assertTrue(prompt.parseRelations("{不是 json").isEmpty());
        assertTrue(prompt.parseRelations(null).isEmpty());

        Map<String, String> ok = prompt.parseRelations("{\"ai:风雪\":\"面试官\",\"me:me\":\"\"}");
        assertEquals(Map.of("ai:风雪", "面试官"), ok, "空值不收录");
    }
}

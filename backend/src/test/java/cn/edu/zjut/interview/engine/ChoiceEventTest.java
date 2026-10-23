package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.engine.events.ChoiceEvent;
import cn.edu.zjut.interview.engine.payload.ChoiceItem;
import cn.edu.zjut.interview.engine.payload.ChoicePayload;
import cn.edu.zjut.interview.engine.payload.ModifyCharacterPayload;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选项事件验收（规格 B7 §任务一.1 / §任务一.6）：
 * 条件禁用 + lock_hint、{@code allowFree} 自由文本转投、disabled 永不匹配、
 * 命中选项执行其动作并续跑主循环。
 *
 * <p>种子剧本里没有 {@code choices} 事件，所以每个用例自建一条 {@code interview_script}
 * + 一个章节（事件 JSON 见各用例），再跑真引擎；LLM 用 {@link MockBean} 替身，不碰网络。
 */
@SpringBootTest
@ActiveProfiles("test")
class ChoiceEventTest {

    @Autowired
    private InterviewEngine engine;
    @Autowired
    private InterviewSessionRepository sessionRepository;
    @Autowired
    private InterviewChapterRepository chapterRepository;
    @Autowired
    private InterviewScriptRepository scriptRepository;
    @Autowired
    private InterviewMessageRepository messageRepository;
    @Autowired
    private InterviewerRepository interviewerRepository;

    @MockBean
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void conditionFailsMarksOptionDisabledWithLockHint() throws Exception {
        Long sessionId = newSession("""
                [
                  {"type": "choices", "allow_free": true, "duration": 12,
                   "options": [
                     {"text": "先讲项目", "condition": "ready == true", "lock_hint": "还没到项目环节"},
                     {"text": "先讲基础"}
                   ]},
                  {"type": "chapter_end", "end_type": "linear", "next_chapter": "end"}
                ]
                """);

        RecordingSink sink = new RecordingSink();
        engine.run(sessionId, sink);

        assertNotNull(sink.choice, "choices 事件必须推送 script:choice");
        assertEquals(2, sink.choice.choices().size(), "两个有文案的选项都要发给前端");
        ChoiceItem locked = sink.choice.choices().get(0);
        assertTrue(locked.disabled(), "condition 不满足的选项必须 disabled");
        assertEquals("还没到项目环节", locked.reason(), "reason 取作者的 lock_hint");
        assertFalse(sink.choice.choices().get(1).disabled());
        assertNull(sink.choice.choices().get(1).reason(), "未锁定的选项没有 reason");
        assertTrue(sink.choice.allowFree(), "allowFree 来自事件的 allow_free");
        assertEquals(12.0, sink.choice.duration(), "duration 原样带给前端");
        // 跨端契约：字段名逐字对照 responses.rs::ChoicePayload / ChoiceItem，缺省项不序列化
        assertEquals("{\"choices\":["
                        + "{\"text\":\"先讲项目\",\"disabled\":true,\"reason\":\"还没到项目环节\"},"
                        + "{\"text\":\"先讲基础\",\"disabled\":false}],"
                        + "\"allowFree\":true,\"duration\":12.0}",
                objectMapper.writeValueAsString(sink.choice));

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(1, session.getEventSequence(), "挂起在本轮，下标停在 choices 之后");
        assertEquals("main", session.getCurrentChapter());
        assertEquals(InterviewEngine.STATUS_RUNNING, session.getStatus(), "选项挂起时剧本仍在跑");

        JsonNode pending = vars(session).get(ChoiceEvent.PENDING_KEY);
        assertNotNull(pending, "挂起状态必须落进 variable_info.pendingChoice");
        assertTrue(pending.path("allowFree").asBoolean(false));
        assertEquals(2, pending.path("options").size(), "pendingChoice 存的是原始 options");
        assertEquals("先讲项目", pending.path("options").get(0).path("text").asText());
        assertFalse(sink.finished, "挂起不是结束");
    }

    @Test
    void allowFreeTextBecomesUserLineAndResumes() throws Exception {
        Long sessionId = newSession("""
                [
                  {"type": "choices", "allow_free": true,
                   "options": [
                     {"text": "先讲项目", "actions": [{"type": "set_var", "content": "picked = project"}]},
                     {"text": "先讲基础", "actions": [{"type": "set_var", "content": "picked = basic"}]}
                   ]},
                  {"type": "chapter_end", "end_type": "linear", "next_chapter": "end"}
                ]
                """);

        engine.run(sessionId, new RecordingSink());

        RecordingSink second = new RecordingSink();
        // 自由文本：两个选项的 text 都对不上，allowFree=true 时按 choice_event.rs:135-146 落成 USER 台词
        engine.submitAndRun(sessionId, "我想先聊聊实习经历", second);

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertNull(vars(session).get(ChoiceEvent.PENDING_KEY), "解决后必须清掉挂起状态");
        assertEquals(InterviewEngine.STATUS_FINISHED, session.getStatus(), "清掉挂起后应续跑到章节结束");
        assertTrue(second.finished, "续跑应推 script:end");

        List<InterviewMessage> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        assertTrue(messages.stream().anyMatch(m -> InterviewMessage.ROLE_USER.equals(m.getRole())
                        && "我想先聊聊实习经历".equals(m.getContent())),
                "没命中选项的自由输入要作为 USER 台词落库");
        assertNull(vars(session).get("picked"), "自由输入不执行任何选项动作");
    }

    @Test
    void disabledOptionNeverMatches() throws Exception {
        Long sessionId = newSession("""
                [
                  {"type": "choices", "allow_free": false,
                   "options": [
                     {"text": "先讲项目", "condition": "ready == true", "lock_hint": "还没到项目环节",
                      "actions": [{"type": "set_var", "content": "picked = project"}]}
                   ]},
                  {"type": "chapter_end", "end_type": "linear", "next_chapter": "end"}
                ]
                """);

        engine.run(sessionId, new RecordingSink());
        int before = messageRepository.findBySessionIdOrderBySeqAsc(sessionId).size();

        RecordingSink second = new RecordingSink();
        // 即便前端把 disabled 项的文案原样提交，condition 不过也永不命中；
        // allowFree=false 时原版不消费 choice 通道，这里保持挂起（第 4 参 = 走「点选项」入口）
        engine.submitAndRun(sessionId, "先讲项目", second, true);

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(1, session.getEventSequence(), "未命中不得推进事件下标");
        assertEquals(InterviewEngine.STATUS_RUNNING, session.getStatus());
        assertNotNull(vars(session).get(ChoiceEvent.PENDING_KEY), "挂起状态必须保留，等玩家重新选择");
        assertEquals(before, messageRepository.findBySessionIdOrderBySeqAsc(sessionId).size(),
                "被拒绝的提交不落任何台词");
        assertNull(vars(session).get("picked"), "disabled 选项的动作不得执行");
        assertFalse(second.finished);
    }

    @Test
    void clickedOptionRunsItsActionsAndResumes() throws Exception {
        Long sessionId = newSession("""
                [
                  {"type": "choices", "allow_free": false,
                   "options": [
                     {"text": "先讲项目", "actions": [{"type": "set_var", "content": "picked = project"}]},
                     {"text": "先讲基础", "actions": [{"type": "set_var", "content": "picked = basic"}]}
                   ]},
                  {"type": "chapter_end", "end_type": "linear", "next_chapter": "end"}
                ]
                """);

        engine.run(sessionId, new RecordingSink());

        RecordingSink second = new RecordingSink();
        // 点选项走 forceChoice=true（对应 POST /script/choice）：allowFree=false 也照样匹配
        engine.submitAndRun(sessionId, "先讲基础", second, true);

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals("basic", vars(session).path("picked").asText(), "命中选项要执行它自己的动作");
        assertNull(vars(session).get(ChoiceEvent.PENDING_KEY));
        assertEquals(InterviewEngine.STATUS_FINISHED, session.getStatus());
        assertTrue(second.finished);
        assertEquals(0, messageRepository.findBySessionIdOrderBySeqAsc(sessionId).size(),
                "命中的选项只执行动作，不额外落用户台词（与原版一致）");
    }

    /** 建一条只含一个 {@code main} 章的剧本 + 一个 running 场次。 */
    private Long newSession(String eventsJson) {
        InterviewScript script = new InterviewScript();
        script.setCode("choice-" + System.nanoTime());
        script.setScriptName("选项验收剧本");
        script.setIntroChapter("main");
        scriptRepository.save(script);

        InterviewChapter chapter = new InterviewChapter();
        chapter.setScriptId(script.getId());
        chapter.setCode("main");
        chapter.setName("选项章");
        chapter.setEvents(eventsJson);
        chapterRepository.save(chapter);

        InterviewSession session = new InterviewSession();
        session.setTitle("选项验收");
        session.setUserId(1L);
        session.setInterviewerId(interviewerRepository.findByCode("strict").orElseThrow().getId());
        session.setScriptId(script.getId());
        session.setJobCode("java");
        session.setCurrentChapter("main");
        session.setEventSequence(0);
        sessionRepository.save(session);
        return session.getId();
    }

    private JsonNode vars(InterviewSession session) throws Exception {
        String raw = session.getVariableInfo();
        return raw == null || raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
    }

    /** 只收 B7 新增事件的桩；chapter / progress 之类忽略。 */
    private static final class RecordingSink implements EventSink {

        private ChoicePayload choice;
        private boolean finished;

        @Override
        public void reply(ReplyEvent event) {
        }

        @Override
        public void narration(String text) {
        }

        @Override
        public void input(String hint) {
        }

        @Override
        public void character(ModifyCharacterPayload payload) {
        }

        @Override
        public void chapter(String code, String name) {
        }

        @Override
        public void progress(String chapter, int eventIndex, int lineCount, JsonNode vars) {
        }

        @Override
        public void finished() {
            finished = true;
        }

        @Override
        public void choice(ChoicePayload payload) {
            choice = payload;
        }
    }
}

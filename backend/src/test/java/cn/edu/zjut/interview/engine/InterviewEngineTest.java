package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.dto.CreateSessionRequest;
import cn.edu.zjut.interview.dto.CreateSessionResponse;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.engine.payload.ModifyCharacterPayload;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * 引擎主循环验收（规格 B5 §7.5）：用种子剧本 {@code java-strict} 的 intro 章跑一遍。
 *
 * <p>intro 的 6 个事件：narration → ai_dialogue → input → ai_dialogue → input → chapter_end。
 * 推进到第一个 input 后 {@code event_sequence} 应为 3；提交一次输入后续跑到下一个 input，为 5。
 * LLM 用 {@link MockBean} 替身，绝不真调网络。
 */
@SpringBootTest
@ActiveProfiles("test")
class InterviewEngineTest {

    @Autowired
    private InterviewEngine engine;
    @Autowired
    private ChatService chatService;
    @Autowired
    private InterviewSessionRepository sessionRepository;
    @Autowired
    private InterviewMessageRepository messageRepository;

    @MockBean
    private LlmClient llmClient;

    @Test
    void advancesIntroToFirstInputThenResumes() {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("【微笑】好的，请用一分钟介绍你自己。");
            return null;
        }).when(llmClient).stream(anyList(), any());

        // F-1 起剧本必须显式指定：不传 scriptCode 就是纯自由会话（不绑剧本）
        CreateSessionResponse created = chatService.createSession(
                new CreateSessionRequest("strict", "java", "java-strict"));
        Long sessionId = created.sessionId();

        RecordingSink first = new RecordingSink();
        engine.run(sessionId, first);

        assertEquals(List.of("narration", "reply", "input"), first.contentEvents());
        assertTrue(first.chapterNames.contains("破冰与自我介绍"), "应推送 chapter 事件");

        InterviewSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(3, session.getEventSequence());
        assertEquals("intro", session.getCurrentChapter());
        assertEquals(InterviewEngine.STATUS_RUNNING, session.getStatus());

        RecordingSink second = new RecordingSink();
        engine.submitAndRun(sessionId, "我叫蔡智豪", second);

        session = sessionRepository.findById(sessionId).orElseThrow();
        assertEquals(5, session.getEventSequence());
        assertEquals("intro", session.getCurrentChapter());
        assertEquals(List.of("reply", "input"), second.contentEvents());

        List<InterviewMessage> messages = messageRepository.findBySessionIdOrderBySeqAsc(sessionId);
        assertTrue(messages.stream().anyMatch(m -> InterviewMessage.ROLE_USER.equals(m.getRole())
                        && "我叫蔡智豪".equals(m.getContent())),
                "用户台词必须落库");
        assertTrue(messages.stream().anyMatch(m -> "plot".equals(m.getMsgType())
                        && m.getContent().startsWith("{旁白: （接下来的剧情演绎提示：")),
                "ai_dialogue 的 prompt 必须以剧情提示信封落库");
        assertTrue(messages.stream().anyMatch(m -> "narration".equals(m.getMsgType())
                        && m.getContent().equals("{旁白: 面试开始。录音与计时已开启。}")),
                "narration 事件整段以旁白信封落库");
    }

    /** 只收内容类事件，忽略 chapter / progress。 */
    private static final class RecordingSink implements EventSink {

        private final List<String> kinds = new ArrayList<>();
        private final List<ReplyEvent> replies = new ArrayList<>();
        private final List<String> chapterNames = new ArrayList<>();
        private boolean finished;

        @Override
        public void reply(ReplyEvent event) {
            kinds.add("reply");
            replies.add(event);
        }

        @Override
        public void narration(String text) {
            kinds.add("narration");
        }

        @Override
        public void input(String hint) {
            kinds.add("input");
        }

        @Override
        public void character(ModifyCharacterPayload payload) {
            kinds.add("character");
        }

        @Override
        public void chapter(String code, String name) {
            kinds.add("chapter");
            chapterNames.add(name);
        }

        @Override
        public void progress(String chapter, int eventIndex, int lineCount, JsonNode vars) {
            kinds.add("progress");
        }

        @Override
        public void finished() {
            kinds.add("finished");
            finished = true;
        }

        List<String> contentEvents() {
            return kinds.stream()
                    .filter(kind -> !"chapter".equals(kind) && !"progress".equals(kind))
                    .toList();
        }
    }
}

package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.engine.events.ChapterEndEvent;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChapterEndEvent} 对齐 {@code chapter_end_event.rs}：
 * linear / branching / ai_judged 三种跳转 + 未知类型。
 */
class ChapterEndEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmClient llmClient = mock(LlmClient.class);
    private final EventSink sink = mock(EventSink.class);
    private final InterviewMessageRepository messageRepository = mock(InterviewMessageRepository.class);

    ChapterEndEventTest() {
        when(messageRepository.findTop20BySessionIdOrderBySeqDesc(anyLong())).thenReturn(List.of());
    }

    @Test
    void linearUsesNextChapter() throws Exception {
        assertEquals("basic", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "linear", "next_chapter": "basic"}
                """, objectMapper.createObjectNode())));
    }

    @Test
    void linearPrefersNextOverNextChapter() throws Exception {
        assertEquals("deep", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "linear", "next": "deep", "next_chapter": "basic"}
                """, objectMapper.createObjectNode())));
    }

    @Test
    void linearWithoutTargetEndsScript() throws Exception {
        assertEquals("end", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "linear"}
                """, objectMapper.createObjectNode())));
    }

    @Test
    void branchingPicksFirstMatchingCondition() throws Exception {
        ObjectNode vars = objectMapper.createObjectNode();
        vars.put("good", true);
        assertEquals("deep", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "branching",
                 "options": [{"condition": "good == true", "next": "deep"},
                             {"condition": "", "next": "basic_more"}]}
                """, vars)));
    }

    @Test
    void branchingFallsBackToDefault() throws Exception {
        ObjectNode vars = objectMapper.createObjectNode();
        vars.put("good", false);
        assertEquals("close", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "branching",
                 "options": [{"condition": "good == true", "next": "deep"},
                             {"default": true, "next": "close"}]}
                """, vars)));
    }

    @Test
    void branchingWithoutAnyMatchEndsScript() throws Exception {
        ObjectNode vars = objectMapper.createObjectNode();
        vars.put("good", false);
        assertEquals("end", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "branching",
                 "options": [{"condition": "good == true", "next": "deep"}]}
                """, vars)));
    }

    @Test
    void aiJudgedMatchesOptionNameFromLlmReply() throws Exception {
        when(llmClient.complete(any())).thenReturn("  我选 deep  ");
        assertEquals("deep", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "ai_judged", "prompt": "评估基础题表现",
                 "options": [{"name": "deep", "next": "deep"},
                             {"name": "basic_more", "next": "basic_more"}]}
                """, objectMapper.createObjectNode())));
    }

    @Test
    void aiJudgedWithoutLlmTerminatesScript() {
        EngineContext ctx = new EngineContext(new InterviewSession(), event("""
                {"type": "chapter_end", "end_type": "ai_judged",
                 "options": [{"name": "deep", "next": "deep"}]}
                """), sink, messageRepository, mock(InterviewChapterRepository.class),
                mock(InterviewerRepository.class), null, objectMapper, "候选人",
                objectMapper.createObjectNode(), "basic");

        assertThrows(IllegalStateException.class, () -> new ChapterEndEvent().execute(ctx));
    }

    @Test
    void unknownEndTypeEndsScript() throws Exception {
        assertEquals("end", new ChapterEndEvent().execute(ctx("""
                {"type": "chapter_end", "end_type": "whatever"}
                """, objectMapper.createObjectNode())));
    }

    private EngineContext ctx(String json, ObjectNode vars) {
        InterviewSession session = new InterviewSession();
        session.setId(1L);
        session.setInterviewerId(1L);
        session.setCurrentChapter("basic");
        return new EngineContext(session, event(json), sink, messageRepository,
                mock(InterviewChapterRepository.class), mock(InterviewerRepository.class),
                llmClient, objectMapper, "候选人", vars, "basic");
    }

    private JsonNode event(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

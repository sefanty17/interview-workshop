package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.ReplyEvent;
import cn.edu.zjut.interview.engine.events.ModifyCharacterEvent;
import cn.edu.zjut.interview.engine.payload.ModifyCharacterPayload;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code modify_character} 的舞台语义验收（对齐
 * {@code script_engine/events/modify_character_event.rs} +
 * {@code game_status.rs:562 onstage_role / 569 offstage_role}）：
 * {@code show_character} 把角色加进在场集合，{@code hide_character} 移出，
 * {@code MAIN} 解析成实际面试官。
 */
class ModifyCharacterEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingSink sink = new RecordingSink();

    @Test
    void showCharacterAddsToPresentRoles() throws Exception {
        EngineContext ctx = ctx("""
                {"type": "modify_character", "character": "观察员", "action": "show_character"}
                """, EngineContext.defaultPresentRoles());

        new ModifyCharacterEvent().execute(ctx);

        assertEquals(Set.of("MAIN", "观察员"), ctx.presentRoles());
        assertEquals(1, sink.payloads.size());
        assertEquals("观察员", sink.payloads.get(0).characterId());
        assertEquals("show_character", sink.payloads.get(0).action());
        assertEquals(List.of("strict", "观察员"), sink.payloads.get(0).presentRoles(),
                "MAIN 要换成面试官 code，其余角色名原样");
    }

    @Test
    void hideCharacterRemovesFromPresentRoles() throws Exception {
        EngineContext ctx = ctx("""
                {"type": "modify_character", "character": "MAIN", "action": "hide_character"}
                """, EngineContext.defaultPresentRoles());

        new ModifyCharacterEvent().execute(ctx);

        assertTrue(ctx.presentRoles().isEmpty(), "MAIN 被请下台后舞台应为空");
        assertEquals(List.of(), sink.payloads.get(0).presentRoles());
    }

    @Test
    void characterDefaultsToMainAndResolvesToInterviewerCode() throws Exception {
        EngineContext ctx = ctx("""
                {"type": "modify_character", "emotion": "紧张"}
                """, EngineContext.defaultPresentRoles());

        new ModifyCharacterEvent().execute(ctx);

        ModifyCharacterPayload payload = sink.payloads.get(0);
        assertEquals("strict", payload.characterId(), "MAIN → interviewer.code，不是硬编码字符串");
        assertEquals("紧张", payload.emotion());
        assertTrue(payload.action() == null, "没写 action 时不序列化");
        assertEquals(List.of("strict"), payload.presentRoles());
        assertEquals("紧张", ctx.vars().path(ModifyCharacterEvent.FORCED_EMOTION_KEY).asText(),
                "情绪仍写进 forcedEmotion 供后续轮次沿用");
    }

    @Test
    void unknownActionKeepsStageUntouched() throws Exception {
        EngineContext ctx = ctx("""
                {"type": "modify_character", "character": "MAIN", "action": "换衣服", "emotion": "平静"}
                """, EngineContext.defaultPresentRoles());

        new ModifyCharacterEvent().execute(ctx);

        assertEquals(Set.of("MAIN"), ctx.presentRoles());
        assertEquals("平静", sink.payloads.get(0).emotion());
    }

    @Test
    void emptyEventDoesNotPushAnything() throws Exception {
        EngineContext ctx = ctx("""
                {"type": "modify_character"}
                """, EngineContext.defaultPresentRoles());

        new ModifyCharacterEvent().execute(ctx);

        assertTrue(sink.payloads.isEmpty(), "既无情绪也无动作，不推空帧");
        assertEquals(Set.of("MAIN"), ctx.presentRoles());
    }

    // ───────────────────────── 桩 ─────────────────────────

    /** 面试官固定为 id=7 / code=strict，用来验证 MAIN 的解析。 */
    private EngineContext ctx(String json, Set<String> presentRoles) throws Exception {
        Interviewer interviewer = new Interviewer();
        interviewer.setId(7L);
        interviewer.setCode("strict");
        interviewer.setName("风雪");
        interviewer.setDisplayRole("技术面试官");

        InterviewerRepository interviewerRepository = mock(InterviewerRepository.class);
        when(interviewerRepository.findById(anyLong())).thenReturn(java.util.Optional.of(interviewer));

        InterviewSession session = new InterviewSession();
        session.setId(1L);
        session.setInterviewerId(7L);
        session.setCurrentChapter("intro");

        ObjectNode vars = objectMapper.createObjectNode();
        return new EngineContext(session, event(json), sink, mock(InterviewMessageRepository.class),
                mock(InterviewChapterRepository.class), interviewerRepository, null, objectMapper,
                "候选人", vars, "intro", presentRoles);
    }

    private JsonNode event(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private static final class RecordingSink implements EventSink {

        private final List<ModifyCharacterPayload> payloads = new ArrayList<>();

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
            payloads.add(payload);
        }

        @Override
        public void chapter(String code, String name) {
        }

        @Override
        public void progress(String chapter, int eventIndex, int lineCount, JsonNode vars) {
        }

        @Override
        public void finished() {
        }
    }
}

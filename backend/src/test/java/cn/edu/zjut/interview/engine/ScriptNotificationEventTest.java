package cn.edu.zjut.interview.engine;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.engine.events.AmbientEvent;
import cn.edu.zjut.interview.engine.events.BackgroundEffectEvent;
import cn.edu.zjut.interview.engine.events.BackgroundEvent;
import cn.edu.zjut.interview.engine.events.DialogEvent;
import cn.edu.zjut.interview.engine.events.MusicEvent;
import cn.edu.zjut.interview.engine.events.PlayerEvent;
import cn.edu.zjut.interview.engine.events.PresentPicEvent;
import cn.edu.zjut.interview.engine.events.SoundEvent;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewMessageRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 展示类事件的 SSE 事件名与 payload 字段验收（规格 B7 §任务一.4、§任务一.6）。
 *
 * <p>断言方式：把 {@link SseEventSink} 挂在一个只做收集的 {@link SseEmitter} 子类上，
 * 拦截 {@code send(SseEventBuilder)} 后自己调 {@code build()} 拿到 Spring 真正要写出去的数据块：
 * <ul>
 *   <li>第 1 块是 {@code "event:<事件名>\ndata:"} —— 事件名在这里，逐字对照
 *       {@code responses.rs:14-28}</li>
 *   <li>第 2 块是 payload 对象 —— 用同一个 Jackson 转成 JSON 树，断字段名与「缺省不序列化」</li>
 * </ul>
 * 事件名与字段名都以 {@code responses.rs} 为唯一真相源，这一层测的就是「别写错字」。
 */
class ScriptNotificationEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Object> chunks = new ArrayList<>();

    private final InterviewMessageRepository messageRepository = mock(InterviewMessageRepository.class);
    private final InterviewerRepository interviewerRepository = mock(InterviewerRepository.class);
    private final EngineContextFactory contextFactory = new EngineContextFactory();

    private final SseEventSink sink = new SseEventSink(new CapturingEmitter(chunks));

    @Test
    void playerPushesScriptPlayerWithTextDisplayNameAndDuration() throws Exception {
        new PlayerEvent().execute(ctx("""
                {"type": "player", "text": "我想先聊聊实习经历", "duration": 2.5}
                """));

        assertEquals("event:script:player", sentName(0));
        JsonNode data = sentData(0);
        assertEquals("我想先聊聊实习经历", data.path("text").asText());
        assertEquals("候选人", data.path("displayName").asText(), "displayName 缺省取玩家昵称");
        assertEquals(2.5, data.path("duration").asDouble());
        assertEquals(3, data.size(), "payload 只有 text / displayName / duration");

        verify(messageRepository).save(any(InterviewMessage.class));
    }

    @Test
    void playerKeepsAuthorDisplayNameAndOmitsDuration() throws Exception {
        new PlayerEvent().execute(ctx("""
                {"type": "player", "text": "……", "displayName": "候选人"}
                """));

        JsonNode data = sentData(0);
        assertEquals("候选人", data.path("displayName").asText());
        assertFalse(data.has("duration"), "没写 duration 就不序列化");
        assertEquals(2, data.size());
    }

    @Test
    void presentPicPushesImagePathAndDefaultScale() throws Exception {
        new PresentPicEvent().execute(ctx("""
                {"type": "present_pic", "imagePath": "bg/architecture.png"}
                """));

        assertEquals("event:script:present-pic", sentName(0));
        JsonNode data = sentData(0);
        assertEquals("bg/architecture.png", data.path("imagePath").asText());
        assertEquals(1.0, data.path("scale").asDouble(), "scale 缺省 1.0");
        assertEquals(2, data.size());
    }

    @Test
    void backgroundPushesImagePathAndDefaultTransition() throws Exception {
        new BackgroundEvent().execute(ctx("""
                {"type": "background", "imagePath": "bg/office.png"}
                """));

        assertEquals("event:script:background", sentName(0));
        JsonNode data = sentData(0);
        assertEquals("bg/office.png", data.path("imagePath").asText());
        assertEquals(1.0, data.path("transition").asDouble(), "transition 缺省 1.0");
        assertEquals(2, data.size());
    }

    @Test
    void backgroundEffectDefaultsToNone() throws Exception {
        new BackgroundEffectEvent().execute(ctx("""
                {"type": "background_effect"}
                """));

        assertEquals("event:script:background-effect", sentName(0));
        JsonNode data = sentData(0);
        assertEquals("none", data.path("effect").asText(), "effect 缺省 none");
        assertEquals(1, data.size());
    }

    /**
     * {@code duration} 原样透传（{@code background_effect_event.rs:64 parse_duration}）：
     * 剧本写 2 就下发 2.0，写 0 就下发 0.0，不归零也不省略。
     *
     * <p>想出去玩啦 5 处 background_effect 的 yaml 写的都是 {@code duration: 0}，
     * 所以实测看到的 0.0 是源数据本身的值，不是引擎丢的。
     */
    @Test
    void backgroundEffectPassesDurationThrough() throws Exception {
        new BackgroundEffectEvent().execute(ctx("""
                {"type": "background_effect", "effect": "sakura", "duration": 2}
                """));

        JsonNode data = sentData(0);
        assertEquals("sakura", data.path("effect").asText());
        assertEquals(2.0, data.path("duration").asDouble());
        assertEquals(2, data.size());

        new BackgroundEffectEvent().execute(ctx("""
                {"type": "background_effect", "effect": "sakura", "duration": 0}
                """));
        assertEquals(0.0, sentData(1).path("duration").asDouble(), "yaml 写 0 就下发 0.0");
    }

    @Test
    void musicPushesPlaybackSpeedOnlyWhenSet() throws Exception {
        new MusicEvent().execute(ctx("""
                {"type": "music", "musicPath": "bgm/tension.mp3"}
                """));
        JsonNode withoutSpeed = sentData(0);
        assertEquals("event:script:music", sentName(0));
        assertEquals("bgm/tension.mp3", withoutSpeed.path("musicPath").asText());
        assertFalse(withoutSpeed.has("playbackSpeed"), "playbackSpeed 未设置就不序列化");

        new MusicEvent().execute(ctx("""
                {"type": "music", "musicPath": "bgm/tension.mp3", "playbackSpeed": 0.8}
                """));
        JsonNode withSpeed = sentData(1);
        assertEquals(0.8, withSpeed.path("playbackSpeed").asDouble());
        assertEquals(2, withSpeed.size());
    }

    @Test
    void soundPushesSoundPath() throws Exception {
        new SoundEvent().execute(ctx("""
                {"type": "sound", "soundPath": "sfx/door.wav"}
                """));

        assertEquals("event:script:sound", sentName(0));
        JsonNode data = sentData(0);
        assertEquals("sfx/door.wav", data.path("soundPath").asText());
        assertEquals(1, data.size());
    }

    @Test
    void ambientPushesVolumeLoopFadeAndStopFlag() throws Exception {
        new AmbientEvent().execute(ctx("""
                {"type": "ambient", "ambientPath": "ambient/rain.mp3"}
                """));

        assertEquals("event:script:ambient", sentName(0));
        JsonNode data = sentData(0);
        assertEquals("ambient/rain.mp3", data.path("ambientPath").asText());
        assertEquals(100.0, data.path("volume").asDouble(), "volume 缺省 100.0");
        assertTrue(data.path("loop").asBoolean(), "loop 缺省 true");
        assertFalse(data.path("stop").asBoolean());
        assertTrue(data.path("fade").asBoolean(), "fade 缺省 true");
        assertEquals(5, data.size());

        // 停止指令：不解析路径，stop=true
        new AmbientEvent().execute(ctx("""
                {"type": "ambient", "ambientPath": "ambient/rain.mp3", "stop": true}
                """));
        assertTrue(sentData(1).path("stop").asBoolean());
    }

    @Test
    void dialogEmitsAiReplyWithSpeakerAndNeverScriptDialog() throws Exception {
        new DialogEvent().execute(ctx("""
                {"type": "dialogue", "character": "MAIN", "text": "我们开始吧。\\n先做个自我介绍。",
                 "emotion": "calm"}
                """));

        // 两次 emit，各 3 个数据块
        assertEquals(6, chunks.size(), "两行台词应各推一次 ai:reply");
        assertEquals("event:ai:reply", sentName(0), "dialog 事件发 ai:reply，不发 script:dialog");
        assertEquals("event:ai:reply", sentName(1));

        JsonNode first = sentData(0);
        assertEquals("reply", first.path("type").asText());
        assertTrue(first.path("isFinal").asBoolean());
        assertEquals(-1.0, first.path("duration").asDouble(), "没写 duration 时原版取 -1.0");
        assertEquals("我们开始吧。", first.path("message").asText());
        assertEquals("calm", first.path("emotion").asText());
        assertEquals("MAIN", first.path("character").asText());
        assertEquals("技术面试官", first.path("displayName").asText(), "displayName 缺省取角色展示名");
        assertEquals("", first.path("displaySubtitle").asText());
        assertEquals("先做个自我介绍。", sentData(1).path("message").asText());
    }

    private EngineContext ctx(String json) {
        return contextFactory.create(objectMapper, sink, messageRepository, interviewerRepository, event(json));
    }

    private JsonNode event(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一次 send 的第 {@code index} 个数据块里的 payload（每 3 块一组：头 + payload + 换行）。 */
    private JsonNode sentData(int sendIndex) {
        return objectMapper.valueToTree(chunks.get(sendIndex * 3 + 1));
    }

    /** 一次 send 的事件名，取自发出去的第一块 {@code "event:<name>\ndata:"}。 */
    private String sentName(int sendIndex) {
        String header = String.valueOf(chunks.get(sendIndex * 3));
        assertTrue(header.startsWith("event:"), "第一块必须是 SSE 事件头，实际: " + header);
        return header.substring(0, header.indexOf('\n'));
    }

    /** 收集 Spring 真正要写出的数据块，绕开真实 HTTP 响应。 */
    private static final class CapturingEmitter extends SseEmitter {

        private final List<Object> chunks;

        private CapturingEmitter(List<Object> chunks) {
            this.chunks = chunks;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                chunks.add(entry.getData());
            }
        }
    }

    /** 统一造 {@link EngineContext}：面试官仓库固定返回「技术面试官」，其余仓库用桩。 */
    private static final class EngineContextFactory {

        EngineContext create(ObjectMapper objectMapper, EventSink sink,
                             InterviewMessageRepository messageRepository,
                             InterviewerRepository interviewerRepository, JsonNode event) {
            Interviewer interviewer = new Interviewer();
            interviewer.setId(1L);
            interviewer.setName("技术面试官");
            interviewer.setDisplayRole("技术面试官");
            when(interviewerRepository.findById(anyLong())).thenReturn(Optional.of(interviewer));

            InterviewSession session = new InterviewSession();
            session.setId(1L);
            session.setInterviewerId(1L);
            session.setCurrentChapter("main");

            ObjectNode vars = objectMapper.createObjectNode();
            return new EngineContext(session, event, sink, messageRepository,
                    mock(InterviewChapterRepository.class), interviewerRepository,
                    null, objectMapper, "候选人", vars, "main");
        }
    }
}

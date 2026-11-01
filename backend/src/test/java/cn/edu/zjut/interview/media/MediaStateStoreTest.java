package cn.edu.zjut.interview.media;

import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.engine.payload.AmbientPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体快照落库的读写语义测试（issue #13）。
 *
 * <p>这里只测「多个事件依次写同一份 JSON 时互相不覆盖」与「读坏数据不炸」两件事：
 * 引擎的 persist 时机已有 {@code InterviewEngineTest} 覆盖，重复测没有意义。
 */
class MediaStateStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void multipleEventsAccumulateIntoOneSnapshot() {
        InterviewSession session = new InterviewSession();

        MediaStateStore.applyBackground(session, mapper, "/backgrounds/office.webp", 1.5);
        MediaStateStore.applyBackgroundEffect(session, mapper, "Rain");
        MediaStateStore.applyMusic(session, mapper, "/music/tense.mp3", 1.2);
        MediaStateStore.applyPresentPic(session, mapper, "/pictures/offer.webp", 1.4);

        JsonNode state = MediaStateStore.parse(session.getMediaState(), mapper);
        assertNotNull(state, "四次写入后应该是一份合法的 JSON 对象");
        assertEquals("/backgrounds/office.webp", state.path("background").path("imagePath").asText());
        assertEquals(1.5, state.path("background").path("transition").asDouble());
        assertEquals("Rain", state.path("backgroundEffect").path("effect").asText());
        assertEquals(1.2, state.path("music").path("playbackSpeed").asDouble());
        assertEquals(1.4, state.path("presentPic").path("scale").asDouble());
    }

    @Test
    void musicWithEmptyPathIsMarkedStopped() {
        InterviewSession session = new InterviewSession();

        MediaStateStore.applyMusic(session, mapper, "/music/a.mp3", null);
        assertTrue(MediaStateStore.parse(session.getMediaState(), mapper)
                .path("music").path("playing").asBoolean(), "有路径时 playing 应为 true");

        MediaStateStore.applyMusic(session, mapper, "", null);
        assertFalse(MediaStateStore.parse(session.getMediaState(), mapper)
                .path("music").path("playing").asBoolean(), "空路径 = 停播，playing 应为 false");
    }

    @Test
    void ambientTracksAreUpsertedByPathNotAppended() {
        InterviewSession session = new InterviewSession();
        AmbientPayload rain = new AmbientPayload("/ambient/rain.wav", 100, true, false, true, null);
        AmbientPayload wind = new AmbientPayload("/ambient/wind.wav", 80, true, false, true, null);

        MediaStateStore.applyAmbient(session, mapper, rain);
        MediaStateStore.applyAmbient(session, mapper, wind);
        assertEquals(2, MediaStateStore.parse(session.getMediaState(), mapper).path("ambient").size());

        // 同一路径再下发一次 = 调音量，不能叠出新轨
        MediaStateStore.applyAmbient(
                session, mapper, new AmbientPayload("/ambient/rain.wav", 40, false, false, true, null));
        JsonNode tracks = MediaStateStore.parse(session.getMediaState(), mapper).path("ambient");
        assertEquals(2, tracks.size(), "同路径再下发不应叠轨");
        JsonNode rainTrack = (tracks.get(0).path("ambientPath").asText().contains("rain"))
                ? tracks.get(0) : tracks.get(1);
        assertEquals(40, rainTrack.path("volume").asDouble());
        assertFalse(rainTrack.path("loop").asBoolean());
    }

    @Test
    void ambientStopRemovesOneTrackOrAll() {
        InterviewSession session = new InterviewSession();
        MediaStateStore.applyAmbient(session, mapper,
                new AmbientPayload("/ambient/rain.wav", 100, true, false, true, null));
        MediaStateStore.applyAmbient(session, mapper,
                new AmbientPayload("/ambient/wind.wav", 100, true, false, true, null));

        MediaStateStore.applyAmbient(session, mapper,
                new AmbientPayload("/ambient/rain.wav", 100, true, true, true, null));
        assertEquals(1, MediaStateStore.parse(session.getMediaState(), mapper).path("ambient").size(),
                "指定路径的 stop 只停该轨");

        MediaStateStore.applyAmbient(session, mapper,
                new AmbientPayload("", 100, true, true, true, null));
        assertEquals(0, MediaStateStore.parse(session.getMediaState(), mapper).path("ambient").size(),
                "空路径的 stop = 全部停止");
    }

    @Test
    void soundIsRecordedButNeverRestored() {
        InterviewSession session = new InterviewSession();
        MediaStateStore.applySound(session, mapper, "/audio_effects/door.wav");

        JsonNode state = MediaStateStore.parse(session.getMediaState(), mapper);
        assertEquals("/audio_effects/door.wav", state.path("sound").path("soundPath").asText());
        // 前端只读 background/music/ambient/presentPic，sound 不在恢复路径里
        assertFalse(state.has("music"), "音效不该顺带写入 BGM");
    }

    @Test
    void brokenSnapshotDegradesToEmptyState() {
        InterviewSession session = new InterviewSession();
        session.setMediaState("{not json");

        JsonNode state = MediaStateStore.read(session, mapper);
        assertNotNull(state);
        assertEquals(0, state.size(), "坏 JSON 应按空状态处理");

        MediaStateStore.applyBackground(session, mapper, "/backgrounds/day.webp", 0);
        assertEquals("/backgrounds/day.webp",
                MediaStateStore.parse(session.getMediaState(), mapper)
                        .path("background").path("imagePath").asText(),
                "坏数据之后的写入应覆盖出一份合法快照");
    }

    @Test
    void parseReturnsNullForEmptyOrNonObject() {
        assertNull(MediaStateStore.parse(null, mapper));
        assertNull(MediaStateStore.parse("  ", mapper));
        assertNull(MediaStateStore.parse("[]", mapper), "数组不是快照对象");
        assertNull(MediaStateStore.parse("oops", mapper), "坏 JSON 在详情接口里留空");
    }
}

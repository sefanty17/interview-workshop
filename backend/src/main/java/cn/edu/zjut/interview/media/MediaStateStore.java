package cn.edu.zjut.interview.media;

import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.engine.payload.AmbientPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 舞台媒体状态持久化（<b>本项目扩展（issue #13）</b>）。
 *
 * <p>原版把 6 类媒体状态放在 {@code game_status} 里，随存档一起写盘；本项目没有存档快照，
 * 对应物就是 {@code interview_session.media_state} 这一列 JSON。剧本事件在推 SSE 的同时
 * 调这里更新快照，引擎每个事件结束后的 {@code persist} 顺带落库 —— 刷新页面后前端读
 * {@code GET /api/sessions/{id}} 就能把背景 / BGM / 环境音 / 浮层恢复到切换时的样子。
 *
 * <p>字段名与 SSE payload 的 camelCase 一致（{@code imagePath} / {@code musicPath} /
 * {@code ambientPath} ...），前端不需要做第二套映射。
 *
 * <p><b>音效只记录不恢复</b>：{@code sound} 是一次性播放（原版注释原话
 * 「without storing state」），刷新后重放会凭空多一声，所以只留最后一条供调试查看。
 */
public final class MediaStateStore {

    /** 背景：{@code {imagePath, transition}}。 */
    public static final String BACKGROUND = "background";
    /** 背景特效：{@code {effect}}。 */
    public static final String BACKGROUND_EFFECT = "backgroundEffect";
    /** BGM：{@code {musicPath, playbackSpeed, playing}}。 */
    public static final String MUSIC = "music";
    /** 音效：{@code {soundPath, at}}，仅记录。 */
    public static final String SOUND = "sound";
    /** 环境音：{@code [{ambientPath, volume, loop, fade}]}，多轨。 */
    public static final String AMBIENT = "ambient";
    /** 展示图片：{@code {imagePath, scale}}。 */
    public static final String PRESENT_PIC = "presentPic";

    private static final Logger log = LoggerFactory.getLogger(MediaStateStore.class);

    private MediaStateStore() {
    }

    /** 从库里的字符串解析出可变 JSON；空值 / 非对象 / 坏 JSON 一律按空表处理。 */
    public static ObjectNode read(InterviewSession session, ObjectMapper mapper) {
        String raw = session == null ? null : session.getMediaState();
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode node = mapper.readTree(raw);
                if (node.isObject()) {
                    return (ObjectNode) node;
                }
            } catch (Exception e) {
                log.warn("[MediaState] media_state 解析失败，按空状态处理：{}", e.toString());
            }
        }
        return mapper.createObjectNode();
    }

    /** 把库里的字符串解析成只读 JSON 给详情接口；空 / 坏数据返回 {@code null}。 */
    public static JsonNode parse(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(raw);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            log.warn("[MediaState] media_state 解析失败，详情里留空：{}", e.toString());
            return null;
        }
    }

    public static void applyBackground(InterviewSession session, ObjectMapper mapper,
                                       String imagePath, double transition) {
        ObjectNode state = read(session, mapper);
        ObjectNode node = mapper.createObjectNode();
        node.put("imagePath", imagePath == null ? "" : imagePath);
        node.put("transition", transition);
        state.set(BACKGROUND, node);
        save(session, state);
    }

    public static void applyBackgroundEffect(InterviewSession session, ObjectMapper mapper, String effect) {
        ObjectNode state = read(session, mapper);
        ObjectNode node = mapper.createObjectNode();
        node.put("effect", effect == null ? "" : effect);
        state.set(BACKGROUND_EFFECT, node);
        save(session, state);
    }

    public static void applyMusic(InterviewSession session, ObjectMapper mapper,
                                  String musicPath, Double playbackSpeed) {
        ObjectNode state = read(session, mapper);
        String path = musicPath == null ? "" : musicPath;
        ObjectNode node = mapper.createObjectNode();
        node.put("musicPath", path);
        if (playbackSpeed != null) {
            node.put("playbackSpeed", playbackSpeed);
        }
        node.put("playing", !path.isBlank());
        state.set(MUSIC, node);
        save(session, state);
    }

    /** 一次性音效：只留最后一条记录，不做恢复（见类注释）。 */
    public static void applySound(InterviewSession session, ObjectMapper mapper, String soundPath) {
        ObjectNode state = read(session, mapper);
        ObjectNode node = mapper.createObjectNode();
        node.put("soundPath", soundPath == null ? "" : soundPath);
        node.put("at", System.currentTimeMillis());
        state.set(SOUND, node);
        save(session, state);
    }

    /**
     * 环境音多轨：同名覆盖、{@code stop} 移除（路径为空 = 全部移除），与前端
     * {@code useScriptRunner} 的差集算法保持同一语义。
     */
    public static void applyAmbient(InterviewSession session, ObjectMapper mapper, AmbientPayload payload) {
        ObjectNode state = read(session, mapper);
        ArrayNode tracks = ensureArray(state, mapper);
        String path = payload == null || payload.ambientPath() == null ? "" : payload.ambientPath();

        if (payload != null && payload.stop()) {
            if (path.isBlank()) {
                tracks.removeAll();
            } else {
                removeByPath(tracks, path);
            }
            state.set(AMBIENT, tracks);
            save(session, state);
            return;
        }
        if (path.isBlank()) {
            // 没有路径的「播放」没有任何可恢复的内容
            return;
        }

        removeByPath(tracks, path);
        ObjectNode track = mapper.createObjectNode();
        track.put("ambientPath", path);
        track.put("volume", payload.volume());
        track.put("loop", payload.loop());
        track.put("fade", payload.fade());
        tracks.add(track);
        state.set(AMBIENT, tracks);
        save(session, state);
    }

    public static void applyPresentPic(InterviewSession session, ObjectMapper mapper,
                                       String imagePath, double scale) {
        ObjectNode state = read(session, mapper);
        ObjectNode node = mapper.createObjectNode();
        node.put("imagePath", imagePath == null ? "" : imagePath);
        node.put("scale", scale);
        state.set(PRESENT_PIC, node);
        save(session, state);
    }

    private static ArrayNode ensureArray(ObjectNode state, ObjectMapper mapper) {
        JsonNode existing = state.get(AMBIENT);
        if (existing instanceof ArrayNode array) {
            return array;
        }
        return mapper.createArrayNode();
    }

    private static void removeByPath(ArrayNode tracks, String path) {
        for (int i = tracks.size() - 1; i >= 0; i--) {
            JsonNode track = tracks.get(i);
            if (track != null && path.equals(track.path("ambientPath").asText(""))) {
                tracks.remove(i);
            }
        }
    }

    private static void save(InterviewSession session, ObjectNode state) {
        if (session != null) {
            session.setMediaState(state.toString());
        }
    }
}

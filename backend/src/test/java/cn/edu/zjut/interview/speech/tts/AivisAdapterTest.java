package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.AivisAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AivisAdapter} 的端点、鉴权与请求体形状（规格 V5c §5.1）。
 *
 * <p>桩是 JDK {@link HttpServer}：任何路径都回固定字节，请求行/头/体记下来供断言。
 * 断言的是「桩收到的形状」（而不是「没抛异常」），这样 {@code Accept} 头漏换、
 * 字段漏传都能被抓到。
 */
class AivisAdapterTest {

    private static final byte[] AUDIO = {1, 2, 3, 4};

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    private volatile String lastPath;
    private volatile String lastAuthorization;
    private volatile String lastAccept;
    private volatile String lastBody;

    @BeforeEach
    void 起桩() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastAccept = exchange.getRequestHeaders().getFirst("Accept");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, AUDIO.length);
            exchange.getResponseBody().write(AUDIO);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void 停桩() {
        server.stop(0);
    }

    @Test
    void 端点与鉴权() {
        adapter("apiKey", "sk-aivis").generate("こんにちは", "");

        // apiUrl 给的是桩地址 + /v1，合成端点必须挂在它后面；多一层少一层都会被这个断言抓到
        assertEquals("/v1/tts/synthesize", lastPath);
        assertEquals("Bearer sk-aivis", lastAuthorization);
    }

    @Test
    void 请求体字段齐全() throws IOException {
        byte[] audio = adapter("apiKey", "sk-aivis", "modelUuid", "a1b2-uuid").generate("こんにちは", "");

        assertArrayEquals(AUDIO, audio, "响应字节原样返回");
        JsonNode body = JSON.readTree(lastBody);
        assertEquals("a1b2-uuid", body.get("model_uuid").asText());
        assertEquals("ja", body.get("language").asText(), "language 沿用上游固定值 ja，前端未提供语种配置");
        assertTrue(body.get("use_ssml").asBoolean());
        assertEquals(44100, body.get("output_sampling_rate").asInt());
        assertEquals("mono", body.get("output_audio_channels").asText());
        assertEquals("こんにちは", body.get("text").asText());
        for (String key : new String[] {"speaking_rate", "emotional_intensity", "tempo_dynamics",
                "pitch", "volume", "leading_silence_seconds", "trailing_silence_seconds",
                "line_break_silence_seconds", "output_format"}) {
            assertTrue(body.has(key), "请求体缺字段 " + key + "：" + lastBody);
        }
    }

    @Test
    void Accept头随格式变() throws IOException {
        TtsSettings mp3 = settings("apiKey", "sk-aivis");
        mp3.setTtsAudioFormat("mp3");
        new AivisAdapter(mp3).generate("hi", "");
        assertEquals("audio/mpeg", lastAccept);
        assertEquals("mp3", JSON.readTree(lastBody).get("output_format").asText());

        TtsSettings wav = settings("apiKey", "sk-aivis");
        wav.setTtsAudioFormat("wav");
        new AivisAdapter(wav).generate("hi", "");
        assertEquals("audio/wav", lastAccept);
    }

    @Test
    void 缺apiKey报缺参数() {
        TtsException e = assertThrows(TtsException.class, () -> adapter().generate("hi", ""));

        assertEquals(TtsErrorKind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("apiKey"), "message 要指名缺哪个字段：" + e.getMessage());
        assertNull(lastPath, "参数不全不该发请求");
    }

    @Test
    void speakerId为0时不带speaker_uuid() throws IOException {
        adapter("apiKey", "sk-aivis", "speakerId", 0).generate("hi", "");
        assertFalse(JSON.readTree(lastBody).has("speaker_uuid"), "speakerId=0 时不得发 speaker_uuid：" + lastBody);

        adapter("apiKey", "sk-aivis", "speakerId", 3).generate("hi", "");
        assertEquals("3", JSON.readTree(lastBody).get("speaker_uuid").asText());
    }

    // ---------------- 工具 ----------------

    private AivisAdapter adapter(Object... keyValues) {
        return new AivisAdapter(settings(keyValues));
    }

    private TtsSettings settings(Object... keyValues) {
        Map<String, Object> cfg = new LinkedHashMap<>(pairs(keyValues));
        cfg.putIfAbsent("apiUrl", baseUrl + "/v1");
        TtsSettings settings = new TtsSettings();
        settings.getTtsEngineConfigs().put(AivisAdapter.TYPE, cfg);
        return settings;
    }

    private static Map<String, Object> pairs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

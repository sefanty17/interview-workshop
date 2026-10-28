package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.CosyvoiceAdapter;
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
 * {@link CosyvoiceAdapter} 的请求体与「JSON 响应 + 二次下载」流程（规格 V5b §6.3）。
 *
 * <p>桩两个上下文：合成端点回 JSON（含 {@code output.audio.url} 指向本桩的 {@code /dl}），
 * {@code /dl} 回固定字节。这样「拿到的音乐是不是下载到的」是可断言的，
 * 而不是「没有抛异常就算过」。
 */
class CosyvoiceAdapterTest {

    private static final byte[] AUDIO = {9, 8, 7, 6, 5};

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private volatile String synthResponse;
    private volatile String lastBody;
    private volatile String lastPath;
    private volatile String lastAuthorization;
    private volatile String lastContentType;

    @BeforeEach
    void 起桩() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/audio/tts/SpeechSynthesizer", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] body = synthResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/dl", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, AUDIO.length);
            exchange.getResponseBody().write(AUDIO);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port + "/api/v1";
        synthResponse = "{\"output\":{\"finish_reason\":\"stop\",\"audio\":{\"url\":\"http://127.0.0.1:"
                + port + "/dl\"}}}";
    }

    @AfterEach
    void 停桩() {
        server.stop(0);
    }

    @Test
    void 请求体是model加input且字段齐全() throws IOException {
        adapter("apiKey", "sk-cosy", "voiceId", "longxiaochun", "model", "cosyvoice-v3.5-flash",
                "language", "zh").generate("你好", "");

        assertEquals("/api/v1/services/audio/tts/SpeechSynthesizer", lastPath);
        assertEquals("Bearer sk-cosy", lastAuthorization);
        assertTrue(lastContentType != null && lastContentType.startsWith("application/json"),
                "Content-Type 由 TtsHttp.post 设成 application/json：" + lastContentType);

        JsonNode body = JSON.readTree(lastBody);
        assertEquals("cosyvoice-v3.5-flash", body.get("model").asText(), "model 在顶层");
        JsonNode input = body.get("input");
        assertEquals("你好", input.get("text").asText());
        assertEquals("longxiaochun", input.get("voice").asText());
        assertEquals("wav", input.get("format").asText());
        assertEquals(24000, input.get("sample_rate").asInt());
        assertEquals(50, input.get("volume").asInt());
        assertEquals(1.0, input.get("rate").asDouble(), 1e-6);
        assertEquals(1.0, input.get("pitch").asDouble(), 1e-6);
        assertEquals("[\"zh\"]", input.get("language_hints").toString(), "language_hints 必须是数组");
        assertFalse(input.has("instruction"), "没配方言就不发 instruction");
    }

    @Test
    void 方言拼成instruction() throws IOException {
        adapter("apiKey", "sk-cosy", "voiceId", "longxiaochun", "dialect", "四川话").generate("你好", "");

        JsonNode input = JSON.readTree(lastBody).get("input");
        assertEquals("用四川话说话。", input.get("instruction").asText());
    }

    @Test
    void finish_reason非stop报BAD_RESPONSE() {
        synthResponse = "{\"output\":{\"finish_reason\":\"length\",\"audio\":{\"url\":\"http://x/dl\"}}}";

        TtsException e = assertThrows(TtsException.class,
                () -> adapter("apiKey", "sk-cosy", "voiceId", "longxiaochun").generate("你好", ""));

        assertEquals(TtsErrorKind.BAD_RESPONSE, e.kind());
        assertTrue(e.getMessage().contains("length"), "message 要带上 finish_reason：" + e.getMessage());
    }

    @Test
    void 按audio_url下载音频() {
        byte[] audio = adapter("apiKey", "sk-cosy", "voiceId", "longxiaochun").generate("你好", "");

        assertArrayEquals(AUDIO, audio, "/dl 返回的字节必须原样交给调用方");
    }

    @Test
    void 缺apiKey报缺参数() {
        TtsException e = assertThrows(TtsException.class,
                () -> adapter("voiceId", "longxiaochun").generate("你好", ""));

        assertEquals(TtsErrorKind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("apiKey"), "message 要指名缺哪个字段：" + e.getMessage());
        assertNull(lastPath, "参数不全不该发请求");
    }

    // ---------------- 工具 ----------------

    private CosyvoiceAdapter adapter(Object... keyValues) {
        Map<String, Object> cfg = new LinkedHashMap<>(pairs(keyValues));
        cfg.putIfAbsent("apiUrl", baseUrl);
        TtsSettings settings = new TtsSettings();
        settings.getTtsEngineConfigs().put(CosyvoiceAdapter.TYPE, cfg);
        return new CosyvoiceAdapter(settings);
    }

    private static Map<String, Object> pairs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

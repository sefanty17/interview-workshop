package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.OpenTtsAdapter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenTtsAdapter} 的地址规整、请求体与参数校验（规格 V5c §5.2）。
 *
 * <p>桩是 JDK {@link HttpServer}：任何路径都回固定字节。重点是端点不能有
 * 重复的 {@code /v1}——{@code /v1/v1/audio/speech} 在真实服务上是 404，
 * 而「能收到请求」并不足以发现它，所以每条用例都断言 {@code lastPath}。
 */
class OpenTtsAdapterTest {

    private static final byte[] AUDIO = {7, 7, 7};

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    private volatile String lastPath;
    private volatile String lastAuthorization;
    private volatile String lastBody;

    @BeforeEach
    void 起桩() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
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
    void 端点去重v1() {
        // 前端默认值就是 …/v1，照抄上游的「先去斜杠再去 /v1 再去斜杠」才不会拼出 /v1/v1
        adapter("apiKey", "sk-open", "apiUrl", baseUrl + "/v1", "voice", "v").generate("你好", "");

        assertEquals("/v1/audio/speech", lastPath);
        assertEquals("Bearer sk-open", lastAuthorization);
    }

    @Test
    void 请求体四字段() throws IOException {
        adapter("apiKey", "sk-open", "voice", "FunAudioLLM/CosyVoice2-0.5B:alex",
                "model", "FunAudioLLM/CosyVoice2-0.5B").generate("你好", "");

        JsonNode body = JSON.readTree(lastBody);
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", body.get("model").asText());
        assertEquals("你好", body.get("input").asText());
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:alex", body.get("voice").asText());
        assertEquals("wav", body.get("response_format").asText());
        assertEquals(4, body.size(), "上游 SpeechRequest 只有四个字段，多一个就是自造协议：" + lastBody);
    }

    @Test
    void 缺apiKey报缺参数() {
        TtsException e = assertThrows(TtsException.class,
                () -> adapter("voice", "v").generate("你好", ""));

        assertEquals(TtsErrorKind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("apiKey"), "message 要指名缺哪个字段：" + e.getMessage());
        assertNull(lastPath, "参数不全不该发请求");
    }

    @Test
    void voice回退全局设置() throws IOException {
        TtsSettings settings = settings("apiKey", "sk-open", "voice", "");
        settings.setTtsVoice("global-voice");
        new OpenTtsAdapter(settings).generate("你好", "");

        assertEquals("global-voice", JSON.readTree(lastBody).get("voice").asText(),
                "引擎级 voice 为空时必须回退全局 ttsVoice（voice_maker.rs:452-456）");
    }

    @Test
    void 空文本报错() {
        TtsException e = assertThrows(TtsException.class,
                () -> adapter("apiKey", "sk-open", "voice", "v").generate("   ", ""));

        assertTrue(e.getMessage().contains("文本"), "message 要说明是文本为空：" + e.getMessage());
        assertNull(lastPath, "空文本不该发请求");
    }

    // ---------------- 工具 ----------------

    private OpenTtsAdapter adapter(Object... keyValues) {
        return new OpenTtsAdapter(settings(keyValues));
    }

    private TtsSettings settings(Object... keyValues) {
        Map<String, Object> cfg = new LinkedHashMap<>(pairs(keyValues));
        cfg.putIfAbsent("apiUrl", baseUrl);
        TtsSettings settings = new TtsSettings();
        settings.getTtsEngineConfigs().put(OpenTtsAdapter.TYPE, cfg);
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

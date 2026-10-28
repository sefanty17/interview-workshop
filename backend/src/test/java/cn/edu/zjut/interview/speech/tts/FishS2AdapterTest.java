package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.FishS2Adapter;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FishS2Adapter} 的 multipart 形状、情绪前缀与响应校验（规格 V5b §6.2）。
 *
 * <p>桩用 JDK {@link HttpServer}，把 {@code /generate} 的<b>原始包体</b>按 UTF-8 收下来：
 * multipart 是手工拼的，只有看原始字节才能确认字段名与分隔符都对。
 */
class FishS2AdapterTest {

    /** 最小合法 WAV 头：12 字节，前 4 是 RIFF、8..12 是 WAVE（适配器只校验这三个位置）。 */
    private static final byte[] WAV = {
            'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 1, 2, 3, 4};

    private HttpServer server;
    private String baseUrl;
    private volatile byte[] responseBody = WAV;
    private volatile String lastBody;
    private volatile String lastPath;
    private volatile String lastContentType;
    private volatile String lastAuthorization;

    @BeforeEach
    void 起桩() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
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
    void 情绪命中时加英文标签前缀() {
        adapter("voice", "paimeng").generate("你好", "高兴");
        assertEquals("[happy]你好", field("text"), "「高兴」必须映射成 [happy] 前缀（fish_s2.rs:58）");

        adapter("voice", "paimeng").generate("你好", "随便");
        assertEquals("你好", field("text"), "未识别情绪不加前缀");
    }

    @Test
    void 文本自带方括号时不重复加前缀() {
        adapter("voice", "paimeng").generate("[happy]你好", "高兴");
        assertEquals("[happy]你好", field("text"), "调用方自己写了标签就原样发（fish_s2.rs:90）");
    }

    @Test
    void multipart含voiceTextParams三个字段() {
        adapter("voice", "paimeng", "apiKey", "sk-test").generate("你好", "温柔");

        assertEquals("/generate", lastPath);
        assertTrue(lastContentType != null && lastContentType.startsWith("multipart/form-data; boundary="),
                "Content-Type 必须是 multipart 且带 boundary：" + lastContentType);
        assertTrue(lastBody.contains("name=\"voice\""), "包体缺 voice 字段：" + lastBody);
        assertTrue(lastBody.contains("name=\"text\""), "包体缺 text 字段：" + lastBody);
        assertTrue(lastBody.contains("name=\"params\""), "包体缺 params 字段：" + lastBody);

        assertEquals("paimeng", field("voice"));
        assertEquals("[gentle]你好", field("text"), "「温柔」→ gentle（fish_s2.rs:82）");
        String params = field("params");
        assertNotNull(params);
        assertTrue(params.contains("max_new_tokens"), "params 必须是 JSON 串：" + params);
        assertEquals("Bearer sk-test", lastAuthorization, "配了 apiKey 就要带 Bearer（本项目扩展）");
    }

    @Test
    void 非WAV响应报BAD_RESPONSE() {
        responseBody = "hello world".getBytes(StandardCharsets.UTF_8);

        TtsException e = assertThrows(TtsException.class,
                () -> adapter("voice", "paimeng").generate("你好", ""));

        assertEquals(TtsErrorKind.BAD_RESPONSE, e.kind());
        assertTrue(e.getMessage().contains("WAV"), "message 要说明不是合法 WAV：" + e.getMessage());
    }

    @Test
    void 缺voice报缺参数() {
        TtsSettings settings = new TtsSettings();
        settings.getTtsEngineConfigs().put(FishS2Adapter.TYPE,
                new LinkedHashMap<>(pairs("apiUrl", baseUrl)));

        TtsException e = assertThrows(TtsException.class,
                () -> new FishS2Adapter(settings).generate("你好", ""));

        assertEquals(TtsErrorKind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("voice"), "message 要指名缺哪个字段：" + e.getMessage());
        assertNull(lastPath, "参数不全不该发请求");
    }

    // ---------------- 工具 ----------------

    private FishS2Adapter adapter(Object... keyValues) {
        Map<String, Object> cfg = new LinkedHashMap<>(pairs(keyValues));
        cfg.putIfAbsent("apiUrl", baseUrl);
        TtsSettings settings = new TtsSettings();
        settings.getTtsEngineConfigs().put(FishS2Adapter.TYPE, cfg);
        return new FishS2Adapter(settings);
    }

    /** 从原始 multipart 包体里取某字段的值（按 {@code name="x"} + 空行 + 值 + CRLF 边界）。 */
    private String field(String name) {
        String marker = "name=\"" + name + "\"";
        int start = lastBody == null ? -1 : lastBody.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = lastBody.indexOf("\r\n\r\n", start) + 4;
        int valueEnd = lastBody.indexOf("\r\n--", valueStart);
        return lastBody.substring(valueStart, valueEnd);
    }

    private static Map<String, Object> pairs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

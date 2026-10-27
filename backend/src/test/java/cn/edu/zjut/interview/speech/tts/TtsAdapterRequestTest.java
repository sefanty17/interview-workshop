package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.Bv2Adapter;
import cn.edu.zjut.interview.speech.tts.adapters.Sbv2Adapter;
import cn.edu.zjut.interview.speech.tts.adapters.Sbv2ApiAdapter;
import cn.edu.zjut.interview.speech.tts.adapters.VitsAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四个适配器的「请求长什么样」（规格 V5a §6.1）。
 *
 * <p>桩用 JDK 自带的 {@link HttpServer}，不引 MockWebServer / WireMock：这里只需要
 * 「收下请求、回一段固定字节」，多一个测试依赖会拖慢整个构建，也和项目「不加依赖」的约定冲突。
 *
 * <p>断言重点是<b>协议细节</b>而不是业务：路径、方法、参数名与值。这些是最容易抄错、
 * 错了又最难查的地方（服务端只会回 400/422，看不出少了个下划线）。
 */
class TtsAdapterRequestTest {

    /** 桩返回的「音频」字节：内容任意，只要前后一致就能验证「原样取出」。 */
    private static final byte[] AUDIO = {1, 2, 3, 4, 5};

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastBody;
    private volatile String lastAccept;

    @BeforeEach
    void 起桩() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().getPath();
            lastQuery = exchange.getRequestURI().getRawQuery();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAccept = exchange.getRequestHeaders().getFirst("Accept");
            exchange.getResponseHeaders().add("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(status.get(), AUDIO.length);
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

    // ---------------- sbv2 ----------------

    @Test
    void sbv2_向voice发POST且参数走查询串() {
        TtsSettings settings = settings("sbv2",
                "apiUrl", baseUrl, "modelName", "interviewer",
                "speakerId", 3, "styleId", 1, "language", "zh", "speed", 1);
        new Sbv2Adapter(settings).generate("你好", "");

        assertEquals("POST", lastMethod);
        assertEquals("/voice", lastPath);
        assertEquals("", lastBody, "上游 sbv2 没有请求体，参数全在 query 里");
        assertEquals("audio/wav", lastAccept);

        Map<String, String> query = query();
        assertEquals("interviewer", query.get("model_name"));
        assertEquals("3", query.get("speaker_id"));
        assertEquals("ZH", query.get("language"), "zh 必须映射成大写 ZH（sbv2.rs:29-34）");
        assertEquals("1.0", query.get("length"), "默认语速 1 时 length 必须与上游字面量一致");
        assertEquals("Neutral", query.get("style"));
        assertEquals("utf-8", query.get("encoding"));
        assertEquals("你好", query.get("text"));
    }

    @Test
    void sbv2_语速换算成长度倒数() {
        // 上游把 length 硬编码成 1.0、不读 speed；前端有语速滑杆，这里按 length = 1/speed 发送
        new Sbv2Adapter(settings("sbv2", "apiUrl", baseUrl, "speed", 2)).generate("测试", "");

        assertEquals("0.5", query().get("length"));
        assertEquals("0.667", TtsParams.num(1.0 / 1.5));
    }

    @Test
    void sbv2_返回字节被原样取出() {
        byte[] audio = new Sbv2Adapter(settings("sbv2", "apiUrl", baseUrl)).generate("测试", "");
        assertArrayEquals(AUDIO, audio);
    }

    @Test
    void sbv2_缺少地址时报缺参数并指名apiUrl() {
        TtsException e = assertThrows(TtsException.class,
                () -> new Sbv2Adapter(settings("sbv2", "apiUrl", "  ")).generate("测试", ""));
        assertEquals(TtsErrorKind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("apiUrl"), "message 必须指名缺哪个字段：" + e.getMessage());
    }

    // ---------------- sbv2api ----------------

    @Test
    void sbv2api_向synthesize发POST且参数在JSON体里() throws IOException {
        TtsSettings settings = settings("sbv2api",
                "apiUrl", baseUrl, "modelName", "interviewer", "speakerId", 2, "styleId", 5, "speed", 2);
        byte[] audio = new Sbv2ApiAdapter(settings).generate("你好", "");

        assertEquals("POST", lastMethod);
        assertEquals("/synthesize", lastPath);
        assertArrayEquals(AUDIO, audio);

        JsonNode body = JSON.readTree(lastBody);
        assertEquals("interviewer", body.get("ident").asText());
        assertEquals(2, body.get("speaker_id").asInt());
        assertEquals(5, body.get("style_id").asInt());
        assertEquals(0.5, body.get("length_scale").asDouble(), 1e-6);
        assertEquals("你好", body.get("text").asText());
    }

    // ---------------- bv2 ----------------

    @Test
    void bv2_向voiceBertVits2发POST且sdp用正确键名() throws IOException {
        TtsSettings settings = settings("bv2",
                "apiUrl", baseUrl, "speakerId", 7, "styleId", 9, "language", "ja", "sdpRatio", 0.35);
        new Bv2Adapter(settings).generate("こんにちは", "");

        assertEquals("POST", lastMethod);
        assertEquals("/voice/bert-vits2", lastPath);

        JsonNode body = JSON.readTree(lastBody);
        assertEquals(7, body.get("id").asInt());
        assertEquals("ja", body.get("lang").asText());
        assertEquals("wav", body.get("format").asText(), "format 取全局 ttsAudioFormat（默认 wav）");
        assertEquals(0.35, body.get("sdp_ratio").asDouble(), 1e-6);
        assertFalse(body.has("sdp_radio"), "sdp_radio 是上游的拼写错误，不该出现");
        assertEquals("こんにちは", body.get("text").asText());
    }

    // ---------------- vits ----------------

    @Test
    void vits_用GET且地址与端口拼在一起() {
        // apiUrl 不带端口、port 单独给：正好验证 urlWithPort 的接入效果
        int port = server.getAddress().getPort();
        TtsSettings settings = settings("vits",
                "apiUrl", "http://127.0.0.1/", "port", port, "speakerId", 4, "language", "zh");
        byte[] audio = new VitsAdapter(settings).generate("你好", "");

        assertEquals("GET", lastMethod, "上游 vits.rs:42 用的是 GET，不是 POST");
        assertEquals("/voice/vits", lastPath);
        assertArrayEquals(AUDIO, audio);

        Map<String, String> query = query();
        assertEquals("4", query.get("id"));
        assertEquals("wav", query.get("format"));
        assertEquals("zh", query.get("lang"));
        assertEquals("你好", query.get("text"));
    }

    // ---------------- 公共行为 ----------------

    @Test
    void 非2xx抛REQUEST_FAILED且message含状态码() {
        status.set(500);
        TtsException e = assertThrows(TtsException.class,
                () -> new Sbv2Adapter(settings("sbv2", "apiUrl", baseUrl)).generate("测试", ""));
        assertEquals(TtsErrorKind.REQUEST_FAILED, e.kind());
        assertTrue(e.getMessage().contains("500"), "message 必须带状态码：" + e.getMessage());
    }

    @Test
    void urlWithPort_三种写法() {
        assertEquals("http://127.0.0.1:9880", TtsParams.urlWithPort("http://127.0.0.1", 9880));
        assertEquals("http://127.0.0.1:9880", TtsParams.urlWithPort("http://127.0.0.1:9880/", 9880));
        assertEquals("http://127.0.0.1:9880", TtsParams.urlWithPort("  http://127.0.0.1//  ", 9880));
        assertEquals("http://host:9880/api", TtsParams.urlWithPort("http://host/api", 9880),
                "端口插在 authority 末尾，不能拼到路径后面");
    }

    // ---------------- 工具 ----------------

    private static TtsSettings settings(String type, Object... keyValues) {
        TtsSettings settings = new TtsSettings();
        Map<String, Object> cfg = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            cfg.put((String) keyValues[i], keyValues[i + 1]);
        }
        settings.getTtsEngineConfigs().put(type, cfg);
        return settings;
    }

    private Map<String, String> query() {
        Map<String, String> params = new LinkedHashMap<>();
        if (lastQuery == null) {
            return params;
        }
        for (String pair : lastQuery.split("&")) {
            int split = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}

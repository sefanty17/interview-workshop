package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.IndexTts2Adapter;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IndexTts2Adapter} 的地址拼接与查询串形状（规格 V5c §5.3）。
 *
 * <p>桩是 JDK {@link HttpServer}：任何路径都回固定 4 字节。IndexTTS2 是 <b>GET + 全查询串</b>，
 * 所以断言对象是 {@code getRequestURI()} 的 path 与 rawQuery，而不是请求体。
 *
 * <p>{@code stream=False} 这种「大写 F 的 Python 字面量」必须逐字断言：写成 {@code false}
 * 服务端按 Python 解析会拿到 {@code False} 之外的东西，而请求仍能发出，不会报错。
 */
class IndexTts2AdapterTest {

    private static final byte[] AUDIO = {5, 6, 7, 8};

    private HttpServer server;
    private int port;

    private volatile String lastPath;
    private volatile String lastHost;
    private volatile String lastRawQuery;

    @BeforeEach
    void 起桩() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastHost = exchange.getRequestHeaders().getFirst("Host");
            lastRawQuery = exchange.getRequestURI().getRawQuery();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, AUDIO.length);
            exchange.getResponseBody().write(AUDIO);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void 停桩() {
        server.stop(0);
    }

    @Test
    void query字段齐全() {
        adapter("apiUrl", "http://127.0.0.1", "port", port, "language", "zh").generate("你好", "中立");

        assertEquals("/voice/indextts/presets", lastPath);
        Map<String, String> q = query();
        assertEquals("0", q.get("id"), "前端没有说话人字段，id 恒 0（与上游 speaker_id: 0 一致）");
        assertEquals("1", q.get("emo_control_method"));
        assertEquals("0.6", q.get("emo_weight"));
        assertEquals("False", q.get("stream"), "stream 必须是 Python 字面量 False（大写 F）");
        assertEquals("120", q.get("max_text_tokens_per_segment"));
        assertEquals("0", q.get("quick_token"));
        assertEquals("zh", q.get("lang"));
        assertEquals("wav", q.get("audio_format"));
        assertEquals("0", q.get("_verify"));
        assertEquals("你好", q.get("text"));
        assertEquals("中立", q.get("emo_id"));
        for (int i = 1; i <= 8; i++) {
            assertTrue(q.containsKey("vec" + i), "查询串缺 vec" + i + "：" + lastRawQuery);
            assertEquals("0.0", q.get("vec" + i));
        }
    }

    @Test
    void 端口拼接生效() {
        // 前端把 host 与 port 拆成两个框：这里只给 http://127.0.0.1，请求能落到桩上就说明端口拼进去了
        adapter("apiUrl", "http://127.0.0.1", "port", port).generate("你好", "");

        assertEquals(port, Integer.parseInt(lastHost.substring(lastHost.indexOf(':') + 1)),
                "Host 头必须带拼接出来的端口（实际：" + lastHost + "）");
        assertEquals("/voice/indextts/presets", lastPath);
    }

    @Test
    void apiUrl已带路径不重复拼() {
        adapter("apiUrl", "http://127.0.0.1:" + port + "/voice/indextts/presets", "port", port)
                .generate("你好", "");

        assertEquals("/voice/indextts/presets", lastPath, "路径不能出现两段：" + lastPath);
    }

    @Test
    void 情绪归一化() {
        adapter("apiUrl", "http://127.0.0.1", "port", port).generate("你好", "心动");
        assertEquals("情动", query().get("emo_id"), "「心动」要归一成服务端情绪表认得的「情动」");

        adapter("apiUrl", "http://127.0.0.1", "port", port).generate("你好", "开心");
        assertEquals("开心", query().get("emo_id"), "其余标签原样透传");
    }

    @Test
    void 返回字节原样() {
        byte[] audio = adapter("apiUrl", "http://127.0.0.1", "port", port).generate("你好", "");

        assertArrayEquals(AUDIO, audio, "桩返回的 4 字节必须原样交给调用方");
    }

    // ---------------- 工具 ----------------

    private IndexTts2Adapter adapter(Object... keyValues) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            cfg.put((String) keyValues[i], keyValues[i + 1]);
        }
        TtsSettings settings = new TtsSettings();
        settings.getTtsEngineConfigs().put(IndexTts2Adapter.TYPE, cfg);
        return new IndexTts2Adapter(settings);
    }

    /** 把 rawQuery 解成 Map；值用 UTF-8 解（中文情绪标签会被百分号编码）。 */
    private Map<String, String> query() {
        Map<String, String> map = new LinkedHashMap<>();
        if (lastRawQuery == null) {
            return map;
        }
        for (String pair : lastRawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return map;
    }
}

package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.GsvAdapter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GsvAdapter} 的模型加载与请求形状（规格 V5b §6.1）。
 *
 * <p>桩是 JDK {@link HttpServer}：{@code /set_gpt_weights} 与 {@code /set_sovits_weights}
 * 回 <b>200 + 空体</b>（这正是 {@code TtsHttp#getAllowEmpty} 要处理的真实形状），
 * {@code /tts} 回固定字节。
 *
 * <p>本类要盯住的两件事都是「跨实例的 static 状态」：
 * <ul>
 *   <li>锁与「已加载权重」都是 static——{@code TtsService} 每次合成现造适配器，
 *       所以同一组合必须由<b>不同实例</b>验证（每个用例都 new 一个新的适配器）；</li>
 *   <li>{@code LOADED} 在整个 JVM 里共享，所以每个用例的模型路径都带上本用例桩的端口，
 *       否则前一个用例记下的组合会让后一个用例「一次都不 set」而误判失败。</li>
 * </ul>
 */
class GsvAdapterTest {

    private static final byte[] AUDIO = {1, 2, 3, 4, 5};

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private int port;

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean failNextTts =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String lastBody;
    private volatile String lastHost;
    private volatile String lastSetQuery;

    @BeforeEach
    void 起桩() throws IOException {
        failNextTts.set(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            counts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            lastHost = exchange.getRequestHeaders().getFirst("Host");
            if (path.endsWith("/tts")) {
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (failNextTts.getAndSet(false)) {
                    byte[] error = "{\"message\":\"模型还在加载\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, error.length);
                    exchange.getResponseBody().write(error);
                } else {
                    exchange.sendResponseHeaders(200, AUDIO.length);
                    exchange.getResponseBody().write(AUDIO);
                }
            } else {
                exchange.getRequestBody().readAllBytes();
                lastSetQuery = exchange.getRequestURI().getRawQuery();
                // 权重设置端点成功时回空体：用 get 会判成 BAD_RESPONSE，这里就是在验 getAllowEmpty
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterEach
    void 停桩() {
        server.stop(0);
    }

    @Test
    void 同一个模型组合只加载一次() {
        TtsSettings settings = settings(GsvAdapter.TYPE_GSV,
                "apiUrl", baseUrl,
                "referenceAudio", "ref.wav", "referenceText", "参考文本", "language", "zh",
                "gptModel", "gpt-" + port + ".ckpt", "sovitsModel", "sovits-" + port + ".pth");

        new GsvAdapter(settings, GsvAdapter.TYPE_GSV).generate("第一句", "");
        assertEquals(1, count("/set_gpt_weights"));
        assertEquals(1, count("/set_sovits_weights"));

        // 关键：换一个新实例（模拟 TtsService 每次现造），同一个组合不该再 set
        new GsvAdapter(settings, GsvAdapter.TYPE_GSV).generate("第二句", "");
        assertEquals(1, count("/set_gpt_weights"), "同一 gpt/sovits 组合的第二次合成不该重复加载 GPT 权重");
        assertEquals(1, count("/set_sovits_weights"), "同一 gpt/sovits 组合的第二次合成不该重复加载 SoVITS 权重");
        assertEquals(2, count("/tts"));
    }

    @Test
    void 模型路径变了会重载() {
        TtsSettings settings = settings(GsvAdapter.TYPE_GSV,
                "apiUrl", baseUrl,
                "referenceAudio", "ref.wav", "referenceText", "参考文本",
                "gptModel", "a-" + port + ".ckpt", "sovitsModel", "a-" + port + ".pth");
        new GsvAdapter(settings, GsvAdapter.TYPE_GSV).generate("第一句", "");
        assertEquals(1, count("/set_gpt_weights"));

        // 用户改了模型路径：上游的 OnceCell 在这里必须重启进程才生效，本项目要求立刻重载
        cfg(settings, GsvAdapter.TYPE_GSV).put("gptModel", "b-" + port + ".ckpt");
        cfg(settings, GsvAdapter.TYPE_GSV).put("sovitsModel", "b-" + port + ".pth");
        new GsvAdapter(settings, GsvAdapter.TYPE_GSV).generate("第二句", "");

        assertEquals(2, count("/set_gpt_weights"), "路径变了必须重新 set GPT 权重");
        assertEquals(2, count("/set_sovits_weights"), "路径变了必须重新 set SoVITS 权重");
        assertTrue(lastSetQuery == null || lastSetQuery.contains("weights_path"),
                "set 端点必须带 weights_path 查询参数：" + lastSetQuery);
    }

    @Test
    void tts请求体字段齐全() throws IOException {
        TtsSettings settings = settings(GsvAdapter.TYPE_GSV,
                "apiUrl", baseUrl,
                "referenceAudio", "D:/ref/interviewer.wav", "referenceText", "你\"好\"\n世界",
                "language", "ja", "gptModel", "", "sovitsModel", "");
        byte[] audio = new GsvAdapter(settings, GsvAdapter.TYPE_GSV).generate("待合成文本", "");

        assertArrayEquals(AUDIO, audio);
        JsonNode body = JSON.readTree(lastBody);
        for (String key : new String[] {"ref_audio_path", "prompt_text", "prompt_lang", "text_lang",
                "media_type", "speed_factor", "text_split_method", "top_k", "top_p",
                "temperature", "parallel_infer", "text"}) {
            assertTrue(body.has(key), "请求体缺字段 " + key + "：" + lastBody);
        }
        assertEquals("D:/ref/interviewer.wav", body.get("ref_audio_path").asText());
        assertEquals("你\"好\"\n世界", body.get("prompt_text").asText(), "带引号换行的参考文本必须原样（所以不能手拼 JSON）");
        assertEquals("ja", body.get("prompt_lang").asText(), "prompt_lang 与 text_lang 合并成 language");
        assertEquals("ja", body.get("text_lang").asText());
        assertEquals("wav", body.get("media_type").asText());
        assertEquals(1.0, body.get("speed_factor").asDouble(), 1e-6);
        assertEquals("cut0", body.get("text_split_method").asText());
        assertEquals(15, body.get("top_k").asInt(), "top_k 上游固定 15（gsv.rs:159）");
        assertEquals(1.0, body.get("top_p").asDouble(), 1e-6);
        assertEquals(1.0, body.get("temperature").asDouble(), 1e-6);
        assertTrue(body.get("parallel_infer").asBoolean());
        assertEquals("待合成文本", body.get("text").asText());
    }

    @Test
    void gptSovits类型把独立端口拼进地址() {
        // 前端 gpt-sovits 是 apiUrl(http://127.0.0.1) + port 两个字段：这里 port 指现桩的端口，
        // 请求能落到桩上 + 路径是 /tts + Host 带该端口，三者合起来证明端口被拼进去了
        TtsSettings settings = settings(GsvAdapter.TYPE_GPT_SOVITS,
                "apiUrl", "http://127.0.0.1", "port", port,
                "referenceAudio", "ref.wav", "referenceText", "", "gptModel", "", "sovitsModel", "");
        byte[] audio = new GsvAdapter(settings, GsvAdapter.TYPE_GPT_SOVITS).generate("你好", "");

        assertArrayEquals(AUDIO, audio);
        assertEquals(1, count("/tts"));
        assertEquals(0, count("/set_gpt_weights"), "两个模型路径都空 → 跳过 set，不报错");
        assertTrue(lastHost != null && lastHost.endsWith(":" + port),
                "Host 头必须带拼接出来的端口（实际：" + lastHost + "）");
    }

    @Test
    void 首次请求失败会重试一次() {
        // 上游 gsv.rs:165-186：网络层失败睡 500ms 再发一次。这里用 503 模拟「服务刚醒还没就绪」
        failNextTts.set(true);
        TtsSettings settings = settings(GsvAdapter.TYPE_GSV,
                "apiUrl", baseUrl, "referenceAudio", "ref.wav", "gptModel", "", "sovitsModel", "");

        byte[] audio = new GsvAdapter(settings, GsvAdapter.TYPE_GSV).generate("你好", "");

        assertArrayEquals(AUDIO, audio, "重试成功后应返回第二次的音频");
        assertEquals(2, count("/tts"), "第一次失败后必须再发一次");
    }

    @Test
    void 缺参考音频报缺参数() {
        TtsSettings settings = settings(GsvAdapter.TYPE_GSV,
                "apiUrl", baseUrl, "referenceAudio", "", "gptModel", "", "sovitsModel", "");

        TtsException e = assertThrows(TtsException.class,
                () -> new GsvAdapter(settings, GsvAdapter.TYPE_GSV).generate("你好", ""));

        assertEquals(TtsErrorKind.NOT_CONFIGURED, e.kind());
        assertTrue(e.getMessage().contains("referenceAudio"), "message 要指名缺哪个字段：" + e.getMessage());
        assertEquals(0, count("/tts"), "参数不全不该发请求");
    }

    // ---------------- 工具 ----------------

    private int count(String path) {
        AtomicInteger value = counts.get(path);
        return value == null ? 0 : value.get();
    }

    private static TtsSettings settings(String type, Object... keyValues) {
        TtsSettings settings = new TtsSettings();
        settings.getTtsEngineConfigs().put(type, new LinkedHashMap<>(pairs(keyValues)));
        return settings;
    }

    private static Map<String, Object> cfg(TtsSettings settings, String type) {
        return settings.getTtsEngineConfigs().get(type);
    }

    private static Map<String, Object> pairs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

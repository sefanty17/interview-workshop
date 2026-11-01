package cn.edu.zjut.interview.speech.asr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qwen 识别链路：真实 HTTP 请求 + 响应解析（用本地 HttpServer 模拟 DashScope）。
 */
class QwenAsrProviderTest {

    private HttpServer server;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 兼容端点收到音频并解析出文本() throws Exception {
        int port = startServer();
        QwenAsrProvider provider = new QwenAsrProvider(settings(port, "sk-test"));

        AsrResult result = provider.recognize(new byte[]{1, 2, 3}, AsrOptions.empty());

        assertEquals("你好", result.text());
        assertEquals("Bearer sk-test", auth.get());
        assertTrue(body.get().contains("\"type\":\"input_audio\""), body.get());
        assertTrue(body.get().contains("data:audio/wav;base64,"), "请求里应包含 WAV data URI");
        assertTrue(body.get().contains("\"model\":\"qwen3-asr-flash\""), body.get());
    }

    @Test
    void 没配Key时不发请求直接报缺凭据() {
        QwenAsrProvider provider = new QwenAsrProvider(settings(9999, ""));

        AsrException e = assertThrows(AsrException.class,
                () -> provider.recognize(new byte[]{1}, AsrOptions.empty()));

        assertEquals(AsrErrorKind.MISSING_CREDENTIALS, e.kind());
    }

    @Test
    void 原生端点会归一到兼容模式地址() {
        AsrProviderConfig cfg = new AsrProviderConfig();
        cfg.setRegion("cn-beijing");
        cfg.setEndpoint("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation");

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                QwenAsrProvider.completionsUrl(cfg));
    }

    private int startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String response = "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
        exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private static AsrSettingsService settings(int port, String key) {
        AsrSettings settings = AsrSettings.defaults();
        settings.setActiveProvider(QwenAsrProvider.ID);
        AsrProviderConfig cfg = new AsrProviderConfig();
        cfg.setRegion("cn-beijing");
        cfg.setEndpoint("http://127.0.0.1:" + port + "/api/v1");
        Map<String, String> keys = new LinkedHashMap<>();
        if (!key.isBlank()) {
            keys.put("cn-beijing", key);
        }
        cfg.setApiKeys(keys);
        settings.setProviderConfigs(Map.of(QwenAsrProvider.ID, cfg));
        return new AsrSettingsService(null, null) {
            @Override
            public AsrSettings load() {
                return settings;
            }
        };
    }
}

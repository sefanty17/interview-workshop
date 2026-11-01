package cn.edu.zjut.interview.speech.asr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * llama-server 识别链路：OpenAI 兼容 multipart 请求 + text 解析。
 */
class LlamaAsrProviderTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 上传WAV并按text字段返回() throws Exception {
        int port = startServer();
        LlamaAsrProvider provider = new LlamaAsrProvider(settings(port));

        AsrResult result = provider.recognize(new byte[]{1, 2, 3}, AsrOptions.empty());

        assertEquals("本地识别结果", result.text());
        assertTrue(body.get().contains("name=\"file\"; filename=\"recording.wav\""), body.get());
        assertTrue(body.get().contains("name=\"model\""), body.get());
        assertTrue(body.get().contains(LlamaAsrProvider.DEFAULT_MODEL), body.get());
    }

    @Test
    void 服务地址支持直接填v1或完整转写端点() {
        assertEquals("http://127.0.0.1:8080/v1/audio/transcriptions",
                LlamaAsrProvider.transcriptionsUrl("http://127.0.0.1:8080"));
        assertEquals("http://127.0.0.1:8080/v1/audio/transcriptions",
                LlamaAsrProvider.transcriptionsUrl("http://127.0.0.1:8080/v1"));
        assertEquals("http://127.0.0.1:8080/v1/audio/transcriptions",
                LlamaAsrProvider.transcriptionsUrl("http://127.0.0.1:8080/v1/audio/transcriptions"));
    }

    private int startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/audio/transcriptions", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String response = "{\"text\":\"本地识别结果\"}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
        exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private static AsrSettingsService settings(int port) {
        AsrSettings settings = AsrSettings.defaults();
        settings.setActiveProvider(LlamaAsrProvider.ID);
        AsrProviderConfig cfg = new AsrProviderConfig();
        cfg.setEndpoint("http://127.0.0.1:" + port);
        settings.setProviderConfigs(Map.of(LlamaAsrProvider.ID, cfg));
        return new AsrSettingsService(null, null) {
            @Override
            public AsrSettings load() {
                return settings;
            }
        };
    }
}

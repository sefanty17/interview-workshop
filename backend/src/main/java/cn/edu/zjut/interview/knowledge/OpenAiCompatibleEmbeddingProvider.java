package cn.edu.zjut.interview.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 任意 OpenAI 兼容的 {@code /embeddings} 客户端（如 bge-m3、text-embedding-3-small）。
 *
 * <p>只用 JDK 自带的 {@link HttpClient}，不引入新依赖：这一个接口的协议足够简单，
 * 为它拉一个 SDK 不划算，也和项目里 {@code LlmClient} 的做法保持一致。
 *
 * <p>维度不写死：以<b>最近一次成功返回</b>的向量长度为准（首次为 0）。
 * 不同服务/模型维度不同，配置里写死一个数字反而会在换模型时静默写错 dim 列。
 */
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最近一次成功返回的维度；0 表示还没成功调过。 */
    private volatile int dimension;

    public OpenAiCompatibleEmbeddingProvider(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> vectors = embedBatch(List.of(text == null ? "" : text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("embedding 服务未返回向量");
        }
        return vectors.get(0);
    }

    /**
     * 批量嵌入，顺序与入参一一对应。
     *
     * <p>失败（非 200 / 解析不了 / 条数对不上）一律抛 {@link IllegalStateException}：
     * 这里不知道调用方想怎么降级（跳过、回退 hashing、还是直接报错），所以不吞异常。
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!isAvailable()) {
            throw new IllegalStateException("embedding 未配置 base-url/api-key，无法调用");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        for (String text : texts) {
            input.add(text == null ? "" : text);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(joinUrl(baseUrl, "/embeddings")))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("embedding 请求失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 请求被中断", e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("embedding 返回 HTTP " + response.statusCode() + "："
                    + abbreviate(response.body()));
        }

        JsonNode data;
        try {
            data = objectMapper.readTree(response.body()).path("data");
        } catch (IOException e) {
            throw new IllegalStateException("embedding 响应不是合法 JSON：" + e.getMessage(), e);
        }
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回条数与入参不一致：期望 " + texts.size()
                    + "，实际 " + data.size());
        }

        List<float[]> out = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw new IllegalStateException("embedding 返回缺少 data[i].embedding");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            out.add(vector);
        }
        // 维度以实际返回为准，供上层写 knowledge_vector.dim
        this.dimension = out.get(0).length;
        return out;
    }

    /** 拼 URL：配置里带不带结尾斜杠都能用。 */
    private static String joinUrl(String base, String path) {
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + path;
    }

    /** 错误响应体可能很长（网关的 HTML 页），只留前 300 字进异常消息。 */
    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}

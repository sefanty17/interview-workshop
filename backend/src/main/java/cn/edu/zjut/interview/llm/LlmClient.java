package cn.edu.zjut.interview.llm;

import cn.edu.zjut.interview.config.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 兼容的流式客户端（规格 B3-BE §三）。
 *
 * <p>只用 JDK 自带的 {@link HttpClient}：{@code POST /chat/completions}，{@code stream=true}，
 * 用 {@code BodyHandlers.ofLines()} 逐行读 SSE。每个 {@code delta.content} 立刻回调，
 * <b>不在这里落库</b>——落库由 {@link cn.edu.zjut.interview.service.ChatService}
 * 在 SentenceSplitter 切出整句之后做。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** SSE 行的前缀，OpenAI 兼容协议固定 {@code data: }。 */
    private static final String DATA_PREFIX = "data:";
    /** 流结束标记。 */
    private static final String DONE = "[DONE]";

    private final LlmProperties properties;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmClient(LlmProperties properties, ConfigService configService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 发起流式请求，每个增量片段回调一次 {@code onDelta}（阻塞直到流结束）。
     *
     * @param messages 完整对话（首条必须是 system）
     * @param onDelta  增量文本回调，可能被调用几十次，内容为 {@code delta.content}
     * @throws LlmException 网络异常 / 非 200 / 流解析失败
     */
    public void stream(List<LlmMessage> messages, Consumer<String> onDelta) {
        stream(messages, LlmOptions.from(configService), onDelta);
    }

    /**
     * 同上，但生成参数由调用方显式给出（{@code ChatService} 每轮从 {@code ConfigService} 读一次）。
     *
     * @param options 模型名 / 温度 / top_p / max_tokens / 超时；{@code onReasoning} 非空时同时回调思考链
     */
    public void stream(List<LlmMessage> messages, LlmOptions options, Consumer<String> onDelta) {
        Consumer<String> onReasoning = options.onReasoning();
        HttpRequest request = buildRequest(messages, options, true);
        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException e) {
            throw new LlmException("LLM 请求超时：" + e.getMessage(), e, true);
        } catch (IOException e) {
            throw new LlmException("LLM 请求失败：" + e.getMessage(), e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM 请求被中断", e, false);
        }

        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                String detail = lines.limit(20).collect(Collectors.joining("\n"));
                throw new LlmException("LLM 返回 HTTP " + response.statusCode() + "：" + detail, null, false);
            }
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next().strip();
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String payload = line.substring(DATA_PREFIX.length()).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                if (DONE.equals(payload)) {
                    break;
                }
                if (onReasoning != null) {
                    String reasoning = extractField(payload, "reasoning_content");
                    if (!reasoning.isEmpty()) {
                        onReasoning.accept(reasoning);
                    }
                }
                String delta = extractDelta(payload);
                if (!delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            // BodyHandlers.ofLines 在连接被对端断开时抛 UncheckedIOException
            Throwable cause = e.getCause();
            throw new LlmException("LLM 流读取失败：" + e.getMessage(), cause == null ? e : cause, false);
        }
    }

    /**
     * 非流式补全，一次性返回整段 {@code choices[0].message.content}。
     *
     * <p>剧本 {@code chapter_end(ai_judged)} 需要「模型只回一个章节名」，流式没有意义，
     * 对应 LingChat {@code LlmClient::complete}（{@code chapter_end_event.rs:195}）。
     *
     * @param messages 完整对话
     * @throws LlmException 网络异常 / 非 200 / 响应解析失败
     */
    public String complete(List<LlmMessage> messages) {
        return complete(messages, LlmOptions.from(configService));
    }

    /** 同上，参数由调用方给出。 */
    public String complete(List<LlmMessage> messages, LlmOptions options) {
        HttpRequest request = buildRequest(messages, options, false);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmException("LLM 请求超时：" + e.getMessage(), e, true);
        } catch (IOException e) {
            throw new LlmException("LLM 请求失败：" + e.getMessage(), e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM 请求被中断", e, false);
        }

        if (response.statusCode() != 200) {
            throw new LlmException("LLM 返回 HTTP " + response.statusCode() + "："
                    + abbreviate(response.body()), null, false);
        }
        try {
            JsonNode node = objectMapper.readTree(response.body());
            JsonNode content = node.path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (IOException e) {
            throw new LlmException("LLM 响应解析失败：" + e.getMessage(), e, false);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500);
    }

    /**
     * 组请求体。模型名 / 温度 / top_p / max_tokens / 超时来自 {@code options}（设置页可改），
     * baseUrl / apiKey 来自 yml（连接凭据）。
     */
    private HttpRequest buildRequest(List<LlmMessage> messages, LlmOptions options, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", options.model());
        body.put("stream", stream);
        body.put("temperature", options.temperature());
        body.put("top_p", options.topP());
        body.put("max_tokens", options.maxTokens());
        ArrayNode array = body.putArray("messages");
        for (LlmMessage message : messages) {
            array.addObject()
                    .put("role", message.role())
                    .put("content", message.content());
        }
        log.info("[LlmClient] 请求 {} 条消息，model={} temperature={} topP={} maxTokens={} timeout={}s",
                messages.size(), options.model(), options.temperature(), options.topP(),
                options.maxTokens(), options.timeoutSeconds());
        // 请求体只在 debug 级别打：里面含完整 prompt（人设、简历、面试回答），
        // INFO 级别落盘等于把用户隐私写进日志。原版用 log.llm_request_body 开关控制，
        // 本项目对应做法是「需要时把本包日志级别调到 DEBUG」。
        // api_key 只在 Authorization 头里，从不进 body，也不打日志。
        if (log.isDebugEnabled()) {
            log.debug("[LlmClient] 请求体：{}", body);
        }
        return HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(options.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    /** 取 {@code choices[0].delta.content}；没有内容（首帧 role、尾帧 finish_reason）返回空串。 */
    private String extractDelta(String payload) {
        return extractField(payload, "content");
    }

    /**
     * 取 {@code choices[0].delta.<field>}。
     *
     * <p>思考链字段名是 {@code reasoning_content}（DeepSeek 系 / OpenAI 兼容实现通用），
     * 对齐 {@code LlmChunk::Reasoning}（{@code llm/mod.rs:101-103}：思考链仅用于展示与统计，
     * 不加入正式回复）。
     */
    private String extractField(String payload, String field) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (IOException e) {
            log.warn("[LlmClient] 跳过无法解析的 SSE 行：{}", payload);
            return "";
        }
        JsonNode value = node.path("choices").path(0).path("delta").path(field);
        return value.isTextual() ? value.asText() : "";
    }

    /** LLM 调用异常；{@code timeout} 决定上层 {@code errorCode} 是 LLM_TIMEOUT 还是 LLM_ERROR。 */
    public static class LlmException extends RuntimeException {

        private final boolean timeout;

        public LlmException(String message, Throwable cause, boolean timeout) {
            super(message, cause);
            this.timeout = timeout;
        }

        public boolean isTimeout() {
            return timeout;
        }
    }
}

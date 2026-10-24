package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmOptions;
import cn.edu.zjut.interview.llm.LlmProperties;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Skill Agent 专用的 OpenAI 兼容流式客户端，带 function-calling（tools）支持。
 * 对应原版 {@code ai_service/llm.rs} 的 {@code complete_stream_with_tools} +
 * {@code skill_agent/core.rs::stream_completion}。
 *
 * <p><b>为什么不是复用 {@link LlmClient}</b>：{@code LlmClient} 的请求体不拼 {@code tools}
 * 字段、{@code LlmMessage} 只有 role/content 两个字段，不支持工具调用协议；而本批约束
 * 只允许改动 {@code agent/} 包，不能扩 {@code llm/} 包。因此这里是工具调用协议的
 * 最小实现，但<b>凭据与生成参数全部复用既有配置</b>（{@link LlmProperties} 的
 * baseUrl/apiKey + {@link LlmOptions#from(ConfigService)} 的 5 个参数），不引入第二套配置来源。
 *
 * <p>协议：{@code POST /chat/completions}，{@code stream=true} +
 * {@code stream_options.include_usage=true}；逐行读 SSE，累积
 * {@code delta.content}（→ message_delta）、{@code delta.reasoning_content}（→ reasoning）、
 * {@code delta.tool_calls} 分片（按 index 拼接 arguments）。
 *
 * <p>思考模式：请求体恒带 {@code thinking: {"type": "enabled"|"disabled"}}，
 * 取自 {@link LlmOptions#thinkingEnabled()}。
 */
@Component
public class SkillAgentLlmClient {

    private static final Logger log = LoggerFactory.getLogger(SkillAgentLlmClient.class);

    private static final String DATA_PREFIX = "data:";
    private static final String DONE = "[DONE]";

    private final LlmProperties properties;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SkillAgentLlmClient(LlmProperties properties, ConfigService configService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** LLM 是否可用（baseUrl / api_key 没配就是不可用，走降级路径）。 */
    public boolean isUsable() {
        return properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    // ───────────────────────── 类型 ─────────────────────────

    /**
     * 一个工具定义（OpenAI function 格式），对应原版 {@code types::ToolDefinition}。
     */
    public record ToolDef(String name, String description, Map<String, Object> parameters) {

        /** {@code {"type":"object","properties":{...},"required":[...]}} 形状的参数 schema。 */
        public static Map<String, Object> schema(Map<String, Map<String, Object>> properties, String... required) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (required.length > 0) {
                schema.put("required", List.of(required));
            }
            return schema;
        }

        /** 空参数 schema（无参工具）。 */
        public static Map<String, Object> emptySchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return schema;
        }

        /** 序列化成 OpenAI 请求体里的一个 tool 对象。 */
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", name);
            fn.put("description", description);
            fn.set("parameters", mapper.valueToTree(parameters));
            ObjectNode node = fn.objectNode();
            node.put("type", "function");
            node.set("function", fn);
            return node;
        }
    }

    /** 循环用的一条消息（role / content / 工具调用三态），对应原版 {@code types::LlmMessage}。 */
    public static final class Msg {

        public final String role;
        public final String content;
        /** assistant 消息携带的工具调用（可为 null）。 */
        public final List<ToolCall> toolCalls;
        /** tool 角色消息对应的调用 id。 */
        public final String toolCallId;

        private Msg(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
        }

        public static Msg system(String content) {
            return new Msg("system", content, null, null);
        }

        public static Msg user(String content) {
            return new Msg("user", content, null, null);
        }

        public static Msg assistant(String content) {
            return new Msg("assistant", content, null, null);
        }

        public static Msg assistantWithTools(String content, List<ToolCall> toolCalls) {
            return new Msg("assistant", content, toolCalls, null);
        }

        public static Msg toolResult(String toolCallId, String content) {
            return new Msg("tool", content, null, toolCallId);
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", role);
            if (content != null) {
                node.put("content", content);
            } else {
                node.putNull("content");
            }
            if (toolCalls != null && !toolCalls.isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : toolCalls) {
                    // 一条调用 = 一个数组元素。早前版本在这里写了两次 addObject()：
                    // 第一次建出空对象（没有 id / type / function），第二次才是真内容，
                    // DeepSeek 直接 422「messages[3]: missing field `id`」。
                    ObjectNode item = calls.addObject();
                    item.put("id", call.id());
                    item.put("type", "function");
                    ObjectNode fn = item.putObject("function");
                    fn.put("name", call.name());
                    fn.put("arguments", call.arguments());
                }
            }
            if (toolCallId != null) {
                node.put("tool_call_id", toolCallId);
            }
            return node;
        }
    }

    /** 一次工具调用（对应原版 {@code types::ToolCall}）。 */
    public record ToolCall(String id, String name, String arguments) {
    }

    /** 一轮流式补全的最终结果（对应 {@code core.rs::stream_completion} 的返回五元组）。 */
    public record Completion(
            String text,
            String reasoning,
            List<ToolCall> toolCalls,
            String finishReason,
            Usage usage) {
    }

    /** Token 用量（对齐 {@code events.rs::Usage}）。 */
    public record Usage(long promptTokens, long completionTokens, long totalTokens, long cachedTokens) {
    }

    // ───────────────────────── 流式补全（带工具） ─────────────────────────

    /**
     * 流式补全一轮：文本/思考增量实时回调，工具调用分片拼好随结果一次返回。
     *
     * @param tools       本轮可用的工具定义；空列表时不拼 tools 字段
     * @param cancelled   取消标志，读到一半置位就提前收
     * @throws LlmClient.LlmException 网络 / 非 200 / 解析失败
     */
    public Completion completeStream(List<Msg> messages,
                                     List<ToolDef> tools,
                                     LlmOptions options,
                                     Consumer<String> onDelta,
                                     Consumer<String> onReasoning,
                                     AtomicBoolean cancelled) {
        HttpRequest request = buildRequest(messages, tools, options);
        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException e) {
            throw new LlmClient.LlmException("LLM 请求超时：" + e.getMessage(), e, true);
        } catch (IOException e) {
            throw new LlmClient.LlmException("LLM 请求失败：" + e.getMessage(), e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClient.LlmException("LLM 请求被中断", e, false);
        }
        if (response.statusCode() != 200) {
            String detail;
            try (Stream<String> lines = response.body()) {
                detail = lines.limit(20).collect(java.util.stream.Collectors.joining("\n"));
            }
            throw new LlmClient.LlmException("LLM 返回 HTTP " + response.statusCode() + "：" + detail, null, false);
        }

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCall> toolMap = new TreeMap<>();
        String finishReason = null;
        Usage usage = null;
        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                if (cancelled.get()) {
                    break;
                }
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
                JsonNode node;
                try {
                    node = objectMapper.readTree(payload);
                } catch (IOException e) {
                    log.warn("[SkillAgentLlmClient] 跳过无法解析的 SSE 行：{}", payload);
                    continue;
                }
                JsonNode choice = node.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                JsonNode content = delta.path("content");
                if (content.isTextual() && !content.asText().isEmpty()) {
                    text.append(content.asText());
                    if (onDelta != null) {
                        onDelta.accept(content.asText());
                    }
                }
                JsonNode think = delta.path("reasoning_content");
                if (think.isTextual() && !think.asText().isEmpty()) {
                    reasoning.append(think.asText());
                    if (onReasoning != null) {
                        onReasoning.accept(think.asText());
                    }
                }
                JsonNode calls = delta.path("tool_calls");
                if (calls.isArray()) {
                    for (JsonNode call : calls) {
                        int index = call.path("index").asInt(toolMap.size());
                        ToolCall existing = toolMap.get(index);
                        String id = call.path("id").asText(existing == null ? "" : existing.id());
                        String name = call.path("function").path("name").asText(existing == null ? "" : existing.name());
                        String arguments = (existing == null ? "" : existing.arguments())
                                + call.path("function").path("arguments").asText("");
                        toolMap.put(index, new ToolCall(id, name, arguments));
                    }
                }
                JsonNode finish = choice.path("finish_reason");
                if (finish.isTextual()) {
                    finishReason = finish.asText();
                }
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    usage = new Usage(
                            usageNode.path("prompt_tokens").asLong(0),
                            usageNode.path("completion_tokens").asLong(0),
                            usageNode.path("total_tokens").asLong(0),
                            usageNode.path("prompt_cache_hit_tokens").asLong(0));
                }
            }
        } catch (RuntimeException e) {
            // BodyHandlers.ofLines 在连接被对端断开时抛 UncheckedIOException（与 LlmClient 同款处理）
            Throwable cause = e.getCause();
            throw new LlmClient.LlmException("LLM 流读取失败：" + e.getMessage(),
                    cause == null ? e : cause, false);
        }
        // 没拿到用量就用「有产出」估算成 0：done 事件带全 0 usage 也没什么用，保持 null 让前端不显示
        if (usage != null && usage.promptTokens() == 0 && usage.completionTokens() == 0) {
            usage = null;
        }
        List<ToolCall> calls = new ArrayList<>(toolMap.values());
        List<ToolCall> fixed = new ArrayList<>();
        int fallbackIndex = 0;
        for (ToolCall call : calls) {
            String id = call.id() == null || call.id().isEmpty()
                    ? "call_" + ProcessHandle.current().pid() + "_" + fallbackIndex : call.id();
            fallbackIndex++;
            fixed.add(new ToolCall(id, call.name(), call.arguments()));
        }
        return new Completion(text.toString(), reasoning.toString(), fixed, finishReason, usage);
    }

    /**
     * 把 thinking 字段写进请求体（1:1 迁移 {@code genai_provider.rs:391-418}）。
     *
     * <p>无条件注入、不区分 provider：DeepSeek Reasoner 这类模型在字段缺失时默认开思考，
     * 不注入就关不掉。本项目只有 OpenAI 兼容通道、没有 MiniMax，故不迁 {@code adaptive} 那支映射。
     * 包内可见，供测试直接调。
     */
    static void putThinking(ObjectNode body, LlmOptions options) {
        body.putObject("thinking").put("type", options.thinkingEnabled() ? "enabled" : "disabled");
    }

    /** 组请求体：与 {@code LlmClient} 同源凭据，多拼 tools / tool_choice / stream_options。 */
    private HttpRequest buildRequest(List<Msg> messages, List<ToolDef> tools, LlmOptions options) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", options.model());
        body.put("stream", true);
        body.put("temperature", options.temperature());
        body.put("top_p", options.topP());
        body.put("max_tokens", options.maxTokens());
        body.putObject("stream_options").put("include_usage", true);
        putThinking(body, options);
        ArrayNode array = body.putArray("messages");
        for (Msg message : messages) {
            array.add(message.toJson(objectMapper));
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (ToolDef tool : tools) {
                toolArray.add(tool.toJson(objectMapper));
            }
            body.put("tool_choice", "auto");
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillAgentLlmClient] 请求 {} 条消息，{} 个工具，model={}",
                    messages.size(), tools == null ? 0 : tools.size(), options.model());
        }
        return HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(options.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }
}

package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qwen ASR（阿里云百炼 / DashScope），对应上游 {@code providers/qwen.rs}。
 *
 * <p>走 OpenAI 兼容的 {@code /compatible-mode/v1/chat/completions}：音频以
 * {@code data:audio/wav;base64,...} 放进 {@code input_audio.data}，结果在
 * {@code choices[0].message.content}。
 *
 * <p>设置页里 qwen 的默认端点是 DashScope 原生地址（{@code /api/v1/services/aigc/...}），
 * 这里统一归一到同一 host 的 compatible-mode 地址；用户填业务空间专属域名也能兼容。
 */
@Component
public class QwenAsrProvider implements AsrProvider {

    public static final String ID = "qwen-asr";
    public static final String DISPLAY = "Qwen ASR（阿里云百炼）";

    private static final int TIMEOUT_SECONDS = 60;
    private static final String DEFAULT_MODEL = "qwen3-asr-flash";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AsrSettingsService settingsService;

    public QwenAsrProvider(@Lazy AsrSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AsrProviderInfo info() {
        return new AsrProviderInfo(
                ID,
                DISPLAY,
                "阿里云百炼 ASR（实时 / 非实时）",
                true,
                configFields(),
                DashScopeRegion.ALL.stream().map(AsrRegionInfo::of).toList());
    }

    /**
     * 设置页字段，照抄上游 {@code config_fields.rs::qwen_asr_config_fields}。
     *
     * <p>没有 {@code model} 字段：模型不是手填的，由 {@code /models} 的下拉写入
     * {@code provider_configs[id].model}，把它再列成一个输入框会出现两份真相。
     */
    private static List<AsrConfigField> configFields() {
        return List.of(
                AsrConfigField.select("region", "地域", DashScopeRegion.DEFAULT.id(),
                        "华北2（北京）与新加坡的域名、API Key、模型列表互相独立，不能混用。"
                                + "下方 API Key 按地域分开保存，切换后显示的是该地域自己的那一份",
                        DashScopeRegion.ALL.stream()
                                .map(r -> new AsrConfigFieldOption(r.id(), r.label()))
                                .toList()),
                AsrConfigField.passwordMap("api_keys", "DashScope API Key", "sk-...",
                        "阿里云百炼（Model Studio）平台 Key，须与所选地域一致。"
                                + "每个地域单独保存：切换上方地域后，这里显示的是该地域自己的 Key"),
                AsrConfigField.text("endpoint", "非实时端点", "留空使用所选地域的默认地址",
                        "留空即可；填业务空间专属域名会归一到相同 host 的 compatible-mode 地址"),
                AsrConfigField.text("ws_endpoint", "实时（流式）端点", "留空使用所选地域的默认地址",
                        "WebSocket 端点，仅流式模型使用（当前整句识别不走这里）"));
    }

    @Override
    public AsrResult recognize(byte[] wav, AsrOptions options) {
        AsrProviderConfig cfg = settingsService.load().getProviderConfigs().get(ID);
        if (cfg == null || blank(cfg.effectiveApiKey())) {
            throw AsrException.missingCredentials("尚未配置阿里云百炼 API Key");
        }
        String model = blank(cfg.getModel()) ? DEFAULT_MODEL : cfg.getModel().trim();
        if (model.contains("realtime")) {
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_REQUEST,
                    "实时模型需要 WebSocket 流式端点（当前整句识别未接流式）；请改选 qwen3-asr-flash");
        }
        String apiKey = cfg.effectiveApiKey().trim();
        String body = requestBody(model, wav, options == null ? null : options.languageHint());
        URI uri = URI.create(completionsUrl(cfg));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                    "连不上 DashScope（" + uri.getHost() + "）：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                    "DashScope 返回 HTTP " + response.statusCode() + "：" + shortText(response.body()));
        }
        return parseResult(response.body());
    }

    /**
     * 把配置里的端点归一到 OpenAI 兼容的 chat/completions。
     *
     * <p>设置页默认填的是 DashScope 原生 {@code /api/v1/services/aigc/...}；
     * 这里只保留 scheme + host，再拼 compatible-mode 路径。留空时按地域回退。
     */
    static String completionsUrl(AsrProviderConfig cfg) {
        String endpoint = cfg == null ? "" : cfg.getEndpoint();
        String origin = origin(endpoint);
        if (blank(origin)) {
            DashScopeRegion region = DashScopeRegion.parse(cfg == null ? null : cfg.getRegion());
            origin = "https://" + region.host();
        }
        String base = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        return base + "/compatible-mode/v1/chat/completions";
    }

    static String requestBody(String model, byte[] wav, String language) {
        String dataUri = "data:audio/wav;base64,"
                + Base64.getEncoder().encodeToString(wav == null ? new byte[0] : wav);
        Map<String, Object> asrOptions = new LinkedHashMap<>();
        if (!blank(language)) {
            asrOptions.put("language", language);
        }
        asrOptions.put("enable_itn", true);

        Map<String, Object> content = Map.of(
                "type", "input_audio",
                "input_audio", Map.of("data", dataUri));
        Map<String, Object> message = Map.of(
                "role", "user",
                "content", List.of(content));
        Map<String, Object> root = Map.of(
                "model", model,
                "messages", List.of(message),
                "stream", false,
                "asr_options", asrOptions);
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.INTERNAL_SERVER_ERROR,
                    "构造 DashScope 请求失败：" + e.getMessage(), e);
        }
    }

    static AsrResult parseResult(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                        "DashScope 返回里没有识别文本：" + shortText(body));
            }
            return new AsrResult(text, null, null, ID);
        } catch (AsrException e) {
            throw e;
        } catch (Exception e) {
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                    "DashScope 返回不是合法 JSON：" + shortText(body), e);
        }
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public List<AsrModelInfo> models(String region) {
        // 地域为空的语义与别处一致：空 = 默认地域（与 AsrProviderConfig.effectiveApiKey 同一条规则）
        return QwenModelCatalog.models(DashScopeRegion.parse(region));
    }

    /** 取 scheme + host + port；解析失败 / 留空返回空串，由调用方按地域回退。 */
    private static String origin(String endpoint) {
        if (blank(endpoint)) {
            return "";
        }
        try {
            URI uri = URI.create(endpoint.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String shortText(String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}

package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 本地 llama-server（llama.cpp）Qwen3-ASR，对应上游 {@code providers/llama.rs}。
 *
 * <p>走 OpenAI 兼容的 {@code POST /v1/audio/transcriptions}（multipart/form-data）：
 * {@code file} 是 16k 单声道 WAV，{@code model} 是 llama-server 的模型名/路径。
 * 识别结果是 JSON 里的 {@code text}。
 *
 * <p>没有地域概念：本地服务的地址与地域无关。
 */
@Component
public class LlamaAsrProvider implements AsrProvider {

    public static final String ID = "llama-asr";
    public static final String DISPLAY = "本地 ASR（llama-server）";

    /** 默认端点与模型来自上游常量，设置页的默认值也读它们（单一真相）。 */
    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8080";
    public static final String DEFAULT_MODEL = "models/Qwen3-ASR-1.7B-Q8_0.gguf";

    private static final int TIMEOUT_SECONDS = 60;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AsrSettingsService settingsService;

    public LlamaAsrProvider(@Lazy AsrSettingsService settingsService) {
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
                "本地 llama-server Qwen3-ASR（整句识别）",
                false,
                configFields(),
                // 空数组 = 无地域概念，前端不渲染地域下拉
                List.of());
    }

    /** 字段顺序照上游 {@code config_fields.rs::llama_asr_config_fields}。 */
    private static List<AsrConfigField> configFields() {
        return List.of(
                new AsrConfigField("endpoint", "服务地址", AsrConfigFieldKind.TEXT, false,
                        DEFAULT_ENDPOINT, "http://127.0.0.1:8080",
                        "llama-server 地址（Qwen3-ASR 本地部署）；局域网部署改 http://<IP>:8080", List.of()),
                new AsrConfigField("model", "模型", AsrConfigFieldKind.TEXT, false,
                        DEFAULT_MODEL, "models/Qwen3-ASR-1.7B-Q8_0.gguf",
                        "llama-server 启动时加载的模型名/路径；须与服务端 /v1/models 一致", List.of()),
                AsrConfigField.password("api_key", "API Key（可选）", "本地服务无需填写",
                        "llama-server 带 --api-key 部署时填写，否则留空"));
    }

    @Override
    public AsrResult recognize(byte[] wav, AsrOptions options) {
        AsrProviderConfig cfg = settingsService.load().getProviderConfigs().get(ID);
        String endpoint = cfg == null || blank(cfg.getEndpoint()) ? DEFAULT_ENDPOINT : cfg.getEndpoint().trim();
        String model = cfg == null || blank(cfg.getModel()) ? DEFAULT_MODEL : cfg.getModel().trim();
        String apiKey = cfg == null ? "" : cfg.effectiveApiKey();

        String boundary = "----TraeAsr" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, model, wav, options == null ? null : options.languageHint());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(transcriptionsUrl(endpoint)))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (!blank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                    "连不上 llama-server（" + endpoint + "）：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                    "llama-server 返回 HTTP " + response.statusCode() + "：" + shortText(response.body()));
        }
        return parseResult(response.body());
    }

    /** 模型清单：llama-server 自己加载模型，这里给一个可选项，模型名与默认路径一致。 */
    @Override
    public List<AsrModelInfo> models(String region) {
        return List.of(new AsrModelInfo(
                DEFAULT_MODEL, "Qwen3-ASR 1.7B（llama-server）", false, true,
                AsrModelInfo.EndpointKind.HTTP));
    }

    /** 把服务地址补成 OpenAI 兼容的转写端点；允许用户直接填完整 URL。 */
    static String transcriptionsUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.endsWith("/v1/audio/transcriptions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/audio/transcriptions";
        }
        return (base.endsWith("/") ? base : base + "/") + "v1/audio/transcriptions";
    }

    static byte[] multipart(String boundary, String model, byte[] wav, String language) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n");
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n");
        if (!blank(language)) {
            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"language\"\r\n\r\n" + language + "\r\n");
        }
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n");
        write(out, "Content-Type: audio/wav\r\n\r\n");
        out.writeBytes(wav == null ? new byte[0] : wav);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    static AsrResult parseResult(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            String text = root.path("text").asText("");
            if (text.isBlank()) {
                throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                        "llama-server 返回里没有 text 字段：" + shortText(body));
            }
            return new AsrResult(text, null, null, ID);
        } catch (AsrException e) {
            throw e;
        } catch (Exception e) {
            throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                    "llama-server 返回不是合法 JSON：" + shortText(body), e);
        }
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String shortText(String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}

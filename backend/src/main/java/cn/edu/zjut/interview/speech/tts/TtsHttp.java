package cn.edu.zjut.interview.speech.tts;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TTS 侧统一的 HTTP 出口：只用 JDK 自带 {@link HttpClient}，不引任何 SDK。
 *
 * <p>对齐上游 {@code adapters/mod.rs::http_client()} 的三件事：超时、环境变量代理、
 * 「非 2xx 抛错时把响应体带上」。不做连接池缓存，理由见 {@link #client}。
 *
 * <p>为什么所有适配器都从这里走而不各自 new 一个 client：错误信息的形状要统一
 * （{@code TTS_REQUEST_FAILED} + 状态码 + 响应体前 200 字）。调试远端 TTS 服务时，
 * 80% 的时间花在「服务端到底返回了什么」上，只报 "HTTP 500" 等于没有信息。
 */
public final class TtsHttp {

    /** 上游默认超时 30 秒（{@code adapters/mod.rs:37}）；设置里给的非法值回落到它。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 错误体截断长度：网关的 HTML 错误页动辄几十 KB，整段塞进异常会让日志不可读。 */
    private static final int ERROR_BODY_PREVIEW = 200;

    private TtsHttp() {
    }

    /**
     * POST 一个 JSON 包体。
     *
     * @param url           完整地址（已含 path）
     * @param query         查询参数，可为 null；值会做 URL 编码
     * @param headers       额外请求头（如 {@code Accept}）
     * @param jsonBody      包体；null 表示「无 body」（上游 sbv2 就是这样发请求的）
     * @param timeoutSeconds 超时秒数，<=0 用 {@value #DEFAULT_TIMEOUT_SECONDS}
     */
    public static byte[] post(String url, Map<String, String> query, Map<String, String> headers,
                              String jsonBody, int timeoutSeconds) {
        HttpRequest.Builder builder = request(url, query, timeoutSeconds);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        if (jsonBody == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        return send(builder.build(), timeoutSeconds);
    }

    /** GET，查询参数走 URL。上游 vits 用的是 GET（{@code vits.rs:42}），不是 POST。 */
    public static byte[] get(String url, Map<String, String> query, int timeoutSeconds) {
        return send(request(url, query, timeoutSeconds).GET().build(), timeoutSeconds);
    }

    private static HttpRequest.Builder request(String url, Map<String, String> query, int timeoutSeconds) {
        if (url == null || url.isBlank()) {
            throw TtsException.notConfigured("TTS 请求地址为空");
        }
        return HttpRequest.newBuilder(URI.create(withQuery(url.trim(), query)))
                .timeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS));
    }

    /** 拼查询串。空格编码成 %20 而不是 +：部分自建服务把 + 当字面量，文本里的空格会变成 "+"。 */
    static String withQuery(String url, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        char separator = url.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(query).entrySet()) {
            builder.append(separator)
                    .append(encode(entry.getKey())).append('=')
                    .append(encode(entry.getValue()));
            separator = '&';
        }
        return builder.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 按当前超时值现建 client。
     *
     * <p>为什么不缓存：超时来自 {@code ttsTimeout}，用户改完设置下一次合成必须立刻生效。
     * 缓存一个 client 就得再维护「超时 → client」的映射，而 TTS 的调用频率是
     * 「一句话一次」，每次建 client（含连接）的开销在几十毫秒量级、可以忽略。
     */
    private static HttpClient client(int timeoutSeconds) {
        int seconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(seconds));
        ProxySelector proxy = proxySelector();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder.build();
    }

    /**
     * 读 {@code HTTPS_PROXY} / {@code HTTP_PROXY}，与上游 {@code http_client()} 的取值顺序一致
     * （先 HTTPS_PROXY 再看 HTTP_PROXY，大小写两种都认）。
     *
     * <p>代理地址写错时<strong>不抛异常</strong>：配置错误的代理不该让整个设置页打不开，
     * 退回直连最多是请求失败，用户还能进来改设置。
     */
    private static ProxySelector proxySelector() {
        String value = firstNonBlank(
                System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
                System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            return ProxySelector.of(new InetSocketAddress(uri.getHost(), port));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 发请求并把非 2xx / 超时 / 空响应体翻译成 {@link TtsException}。
     *
     * <p>空响应体单独判成 {@code TTS_BAD_RESPONSE}：上游服务「200 但什么都没返回」时，
     * 若原样返回空数组，前端会得到 0 字节音频并在播放器里静默失败——报错比静默好。
     */
    private static byte[] send(HttpRequest request, int timeoutSeconds) {
        String url = request.uri().toString();
        HttpResponse<byte[]> response;
        try {
            response = client(timeoutSeconds).send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw TtsException.timeout("TTS 请求超时：" + url);
        } catch (IOException e) {
            throw TtsException.requestFailed("TTS 请求失败：" + e.getMessage() + "（" + url + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw TtsException.requestFailed("TTS 请求被中断：" + url);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw TtsException.requestFailed("TTS 上游返回 HTTP " + status + "：" + preview(response.body()));
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw TtsException.badResponse("TTS 上游返回了空响应体（HTTP " + status + "）：" + url);
        }
        return body;
    }

    private static String preview(byte[] body) {
        if (body == null || body.length == 0) {
            return "（无响应体）";
        }
        String text = new String(body, StandardCharsets.UTF_8).strip();
        return text.length() <= ERROR_BODY_PREVIEW
                ? text
                : text.substring(0, ERROR_BODY_PREVIEW) + "…";
    }
}

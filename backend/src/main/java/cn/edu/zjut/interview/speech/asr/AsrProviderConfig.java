package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 provider 的配置，字段逐个对齐 {@code settings.rs::ProviderConfig}。
 *
 * <p>JSON 键用 <strong>snake_case</strong>，因为上游结构体没有 {@code rename_all}，
 * 序列化出来的就是 Rust 字段名；LingChat 前端 {@code api/services/asr.ts} 读的也是
 * {@code api_keys} / {@code ws_endpoint} / {@code region}。
 *
 * <p>为什么显式关掉 getter/setter 自动探测：Jackson 会把「字段上的显式名字」
 * 和「getter 推导出的 camelCase 名字」当成两个不同属性，结果同一份配置在 JSON 里
 * 出现 {@code api_keys} 与 {@code apiKeys} 两份。字段名只写一遍在这里。
 *
 * <p><b>新增字段必须同步加到这里</b>：设置页把 {@code config_fields[].key} 写成
 * 本对象的顶层键，而本类忽略未知字段——只加元数据不加字段，用户填的值会被静默丢弃。
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public class AsrProviderConfig {

    /**
     * 按地域分存的 API Key，键为 {@link DashScopeRegion#id()}。
     *
     * <p>各地域的 Key 互相独立、不能混用，共用一个字段会让「切地域」变成「必须重填」。
     * 设置页只显示当前地域的那一个框，读写都落在这里。
     */
    @JsonProperty("api_keys")
    private Map<String, String> apiKeys = new LinkedHashMap<>();

    /**
     * <b>历史字段</b>：引入 {@code api_keys} 之前的唯一 Key。
     *
     * <p>仍被 {@link #effectiveApiKey()} 作为兜底读取——老配置在迁移前、以及
     * llama-asr 这种无地域概念的 provider 都靠它；设置页不再写入。
     */
    @JsonProperty("api_key")
    private String apiKey = "";

    /** 非实时（同步）端点。 */
    @JsonProperty("endpoint")
    private String endpoint = "";

    /**
     * 实时（流式）WebSocket 端点。
     *
     * <p>与 {@code endpoint} 分开：协议不同，且用户可能只覆盖其一
     * （业务空间专属域名的 HTTP 与 WS 未必同源）。
     */
    @JsonProperty("ws_endpoint")
    private String wsEndpoint = "";

    @JsonProperty("model")
    private String model = "";

    /** DashScope 地域 id（见 {@link DashScopeRegion}）；空/未知 = 默认地域。 */
    @JsonProperty("region")
    private String region = "";

    /**
     * 当前地域生效的 API Key。
     *
     * <p>键必须用<strong>解析后</strong>的地域 id 而非 {@code region} 原值：
     * 空/拼错的地域在别处一律回退默认地域，这里用同一个键才对得上；否则会出现
     * 「端点按北京派生、Key 却按未知 id 查不到」而静默变空。
     *
     * <p>回退链：{@code api_keys[地域]}（非空白）→ 顶层 {@code api_key}。
     */
    public String effectiveApiKey() {
        String key = apiKeys == null ? null : apiKeys.get(DashScopeRegion.parse(region).id());
        if (key != null && !key.isBlank()) {
            return key;
        }
        return apiKey == null ? "" : apiKey;
    }

    public Map<String, String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(Map<String, String> apiKeys) {
        this.apiKeys = apiKeys == null ? new LinkedHashMap<>() : apiKeys;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint;
    }

    public String getWsEndpoint() {
        return wsEndpoint;
    }

    public void setWsEndpoint(String wsEndpoint) {
        this.wsEndpoint = wsEndpoint == null ? "" : wsEndpoint;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region == null ? "" : region;
    }
}

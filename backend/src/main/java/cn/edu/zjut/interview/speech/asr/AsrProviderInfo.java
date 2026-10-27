package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * provider 的静态元数据，对应上游 {@code provider_meta.rs::ProviderInfo}。
 *
 * <p>为什么把「设置页要渲染哪些字段」放在后端：新增一个 provider 只需要后端加一个
 * {@link AsrProvider} 实现，前端零改动。前端若自己维护字段表，两边一定会漂移
 * （典型症状：后端新字段永远填不进去）。
 *
 * @param id                唯一 id，写进设置的 {@code active_provider}
 * @param displayName       UI 显示名
 * @param description       一句话描述
 * @param supportsStreaming 是否支持流式协议（前端据此决定流式开关可用性）
 * @param configFields      设置页要渲染的字段
 * @param regions           可选地域；空 = 该 provider 无地域概念（本地服务），前端不渲染下拉
 */
public record AsrProviderInfo(
        String id,
        @JsonProperty("display_name") String displayName,
        String description,
        @JsonProperty("supports_streaming") boolean supportsStreaming,
        @JsonProperty("config_fields") List<AsrConfigField> configFields,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AsrRegionInfo> regions) {

    public AsrProviderInfo {
        configFields = configFields == null ? List.of() : List.copyOf(configFields);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}

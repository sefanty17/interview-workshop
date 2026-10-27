package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 模型元数据，对应上游 {@code provider_types.rs::ModelInfo}：前端模型下拉的一项。
 *
 * <p>为什么不给「能力位」（热词 / 语言提示 / 旧 body 格式）也下发：前端只关心
 * 「能不能选流式」，其余能力是 provider 内部发请求时用的门控，下发只会让两处
 * 判断同时存在、然后不一致。
 *
 * @param id                模型 id（写进 {@code provider_configs[id].model}）
 * @param displayName       UI 显示名
 * @param supportsStreaming 是否支持流式（前端流式开关可用性的权威判定）
 * @param isDefault         是否默认模型（配置里 model 为空时生效）
 * @param endpointKind      选中该模型时应把哪个端点字段切到对应协议；{@code null} = 不干预
 */
public record AsrModelInfo(
        String id,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("supports_streaming") boolean supportsStreaming,
        @JsonProperty("is_default") boolean isDefault,
        @JsonProperty("endpoint_kind") EndpointKind endpointKind) {

    /** 端点类型，序列化成 {@code http} / {@code ws}（上游 serde 同名枚举）。 */
    public enum EndpointKind {

        /** 同步（非实时）端点 → {@code provider_configs[id].endpoint}。 */
        HTTP("http"),
        /** 实时 WebSocket 端点 → {@code provider_configs[id].ws_endpoint}。 */
        WS("ws");

        private final String wireName;

        EndpointKind(String wireName) {
            this.wireName = wireName;
        }

        @JsonValue
        public String wireName() {
            return wireName;
        }
    }
}

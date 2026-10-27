package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 下发给前端的单个地域信息，对应上游 {@code region.rs::AsrRegionInfo}。
 *
 * <p>字段名是 {@code label} 而不是 {@code displayName}：上游就是这么序列化的
 * （{@code ProviderInfo.regions} 里的元素），前端切地域靠它显示下拉项。
 * 端点值也一并下发，前端切地域时直接把端点填进输入框——端点默认值的单一真相在后端，
 * 前端不复刻拼接规则。
 *
 * @param id           地域 id（写进 {@code provider_configs[id].region}）
 * @param label        下拉显示名
 * @param httpEndpoint 该地域的同步端点
 * @param wsEndpoint   该地域的实时端点
 */
public record AsrRegionInfo(
        String id,
        String label,
        @JsonProperty("http_endpoint") String httpEndpoint,
        @JsonProperty("ws_endpoint") String wsEndpoint) {

    /** 从枚举投影，端点由 {@link DashScopeRegion#host()} 派生。 */
    public static AsrRegionInfo of(DashScopeRegion region) {
        return new AsrRegionInfo(region.id(), region.label(), region.httpEndpoint(), region.wsEndpoint());
    }
}

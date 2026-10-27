package cn.edu.zjut.interview.speech.asr;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Qwen ASR（阿里云百炼 / DashScope），对应上游 {@code providers/qwen.rs}。
 *
 * <p><b>V1 只有壳</b>：元信息与模型表是真的（设置页要能选它、看到地域与模型），
 * 但 {@link #recognize} 一律抛「缺少凭据」。这不是偷懒——真实的 DashScope 调用
 * 需要请求体格式、热词门控、两种 audio content 格式分流，属于 V4，硬塞一个
 * 半成品实现只会让前端拿到看不懂的失败。
 */
@Component
public class QwenAsrProvider implements AsrProvider {

    public static final String ID = "qwen-asr";
    public static final String DISPLAY = "Qwen ASR（阿里云百炼）";

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
                // 不是 api_key 而是按地域分开存的 api_keys：北京与新加坡的 Key 互相独立，
                // 切换地域不能把另一个地域已填的 Key 冲掉
                AsrConfigField.passwordMap("api_keys", "DashScope API Key", "sk-...",
                        "阿里云百炼（Model Studio）平台 Key，须与所选地域一致。"
                                + "每个地域单独保存：切换上方地域后，这里显示的是该地域自己的 Key"),
                AsrConfigField.text("endpoint", "非实时端点", "留空使用所选地域的默认地址",
                        "multimodal-generation 端点。填业务空间专属域名可覆盖，形如 "
                                + "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/"
                                + "multimodal-generation/generation"),
                AsrConfigField.text("ws_endpoint", "实时（流式）端点", "留空使用所选地域的默认地址",
                        "WebSocket 端点，仅流式模型使用。形如 "
                                + "wss://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/inference"));
    }

    @Override
    public AsrResult recognize(byte[] wav, AsrOptions options) {
        // V4 接 DashScope multimodal-generation；本批只保证端点与错误码正确
        throw AsrException.missingCredentials("尚未配置阿里云百炼 API Key");
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
}

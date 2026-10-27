package cn.edu.zjut.interview.speech.asr;

import java.util.ArrayList;
import java.util.List;

/**
 * DashScope 模型目录与协议能力表，移植 {@code qwen_models.rs} 的 {@code QWEN_MODELS}。
 *
 * <p>模型清单、是否流式、端点预设全部从这张表派生——单一真相，避免
 * 「加了模型忘了改判断」导致请求走错链路（流式模型发到同步端点，DashScope 直接 400
 * "url error"，错误信息完全指不到原因）。
 *
 * <p>表里保留了 {@code listed=false} 的历史模型：它们不出现在设置页，但老配置里
 * 可能存着这些 id，按 id 查协议的用途（V4 拼请求时）必须仍能查到。
 */
final class QwenModelCatalog {

    private static final List<DashScopeRegion> BOTH_REGIONS =
            List.of(DashScopeRegion.CN_BEIJING, DashScopeRegion.AP_SOUTHEAST_1);
    private static final List<DashScopeRegion> CN_ONLY = List.of(DashScopeRegion.CN_BEIJING);

    /**
     * 模型规格。
     *
     * @param listed       是否出现在设置页的模型列表里（false = 隐藏的历史模型，协议行为照旧）
     * @param wsRealtime   走 WebSocket 实时端点（false = 同步 HTTP 端点）
     * @param defaultBatch 所属地域是否以它作为同步默认模型
     */
    private record Spec(String id, String display, boolean listed, boolean wsRealtime,
                        List<DashScopeRegion> regions, boolean defaultBatch) {
    }

    /** 按协议实接情况维护；异步任务类（{@code *-filetrans}、{@code paraformer-v2}）协议未接入，不列出。 */
    private static final List<Spec> MODELS = List.of(
            new Spec("qwen-audio-3.0-asr-flash", "Qwen-Audio-3.0-ASR-Flash（非实时）",
                    true, false, BOTH_REGIONS, true),
            // 保留 3.0 作默认：新增模型不该改变既有用户的行为
            new Spec("qwen-audio-3.1-asr-flash", "Qwen-Audio-3.1-ASR-Flash（非实时）",
                    true, false, BOTH_REGIONS, false),
            // 历史默认非流式模型：DashScope 已把它归入实时族，但本项目同步路径一直在用它
            // 且实测可用。不列出是因为它与 2026-02-28 那条同名不同协议，摆在列表里会被误选
            new Spec("fun-asr-realtime", "Fun-ASR-Realtime（非实时·历史协议）",
                    false, false, BOTH_REGIONS, false),
            // 流式首选：现有 WS 客户端是对着这个模型实证写出来的
            new Spec("paraformer-realtime-v2", "Paraformer-Realtime-V2",
                    true, true, BOTH_REGIONS, false),
            // 文档的热词支持表只在北京地域列出它，故保守标为北京独有
            new Spec("fun-asr-realtime-2026-02-28", "Fun-ASR-Realtime（2026-02-28·历史）",
                    false, true, CN_ONLY, false),
            new Spec("qwen-audio-3.1-asr-flash-streaming", "Qwen-Audio-3.1-ASR-Flash-Streaming（实时）",
                    true, true, BOTH_REGIONS, false));

    private QwenModelCatalog() {
    }

    /** 按地域过滤的模型清单，不含隐藏的历史模型。 */
    static List<AsrModelInfo> models(DashScopeRegion region) {
        List<AsrModelInfo> models = new ArrayList<>();
        for (Spec spec : MODELS) {
            if (spec.listed() && spec.regions().contains(region)) {
                models.add(new AsrModelInfo(
                        spec.id(),
                        spec.display(),
                        spec.wsRealtime(),
                        spec.defaultBatch(),
                        spec.wsRealtime() ? AsrModelInfo.EndpointKind.WS : AsrModelInfo.EndpointKind.HTTP));
            }
        }
        return models;
    }
}

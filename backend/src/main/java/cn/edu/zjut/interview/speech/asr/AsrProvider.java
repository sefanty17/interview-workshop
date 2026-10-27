package cn.edu.zjut.interview.speech.asr;

import java.util.List;

/**
 * 云 / 本地 ASR provider 的统一接口，对齐上游 {@code provider.rs::AsrProvider}。
 *
 * <p>V1 只有 {@link MockAsrProvider} 会真的返回文本；qwen / llama 是壳，
 * 但注册表里必须在场——设置页要能列出它们，错误码也要能提前对好（否则 V4 接接口时
 * 前端拿到的是 500 而不是「没配 key」）。
 */
public interface AsrProvider {

    /** 注册表 id，写进设置的 {@code active_provider}。 */
    String id();

    /** 元信息（含设置页字段），设置页据此渲染表单。 */
    AsrProviderInfo info();

    /**
     * 整段 WAV 识别。
     *
     * @param wav     16kHz 单声道 16bit WAV 字节
     * @param options 本次调用的可选参数（语言提示 + 热词）
     * @throws AsrException 任何失败都以领域异常抛出，不让 HTTP 细节外泄
     */
    AsrResult recognize(byte[] wav, AsrOptions options);

    /** 是否支持流式协议（前端据此决定流式开关可用）。默认不支持。 */
    default boolean supportsStreaming() {
        return false;
    }

    /** 模型能力表；无模型概念的 provider 返回空列表。 */
    default List<AsrModelInfo> models(String region) {
        return List.of();
    }
}

package cn.edu.zjut.interview.speech.asr;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地 llama-server（llama.cpp）Qwen3-ASR，对应上游 {@code providers/llama.rs}。
 *
 * <p><b>V1 只有壳</b>：元信息是真的（设置页能选它、能填本地地址），
 * 但 {@link #recognize} 直接报「尚未接入」——真接要处理 OpenAI 兼容 multipart、
 * 结果里 {@code <asr_text>} 标记的切分、SSE 增量，属于后续批次。
 *
 * <p>它没有 {@code regions}：本地服务的地址与地域无关，套 DashScope 的地域派生规则
 * 会把它污染成云端域名。
 */
@Component
public class LlamaAsrProvider implements AsrProvider {

    public static final String ID = "llama-asr";
    public static final String DISPLAY = "本地 ASR（llama-server）";

    /** 默认端点与模型来自上游常量，设置页的默认值也读它们（单一真相）。 */
    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8080";
    public static final String DEFAULT_MODEL = "models/Qwen3-ASR-1.7B-Q8_0.gguf";

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
                        "从上方模型列表选择，或用 /v1/models 查询服务端全名", List.of()),
                AsrConfigField.password("api_key", "API Key（可选）", "本地服务无需填写",
                        "llama-server 带 --api-key 部署时填写，否则留空"));
    }

    @Override
    public AsrResult recognize(byte[] wav, AsrOptions options) {
        throw new AsrException(AsrErrorKind.PROVIDER_FAILED, HttpStatus.BAD_GATEWAY,
                "llama-asr 需要本机 llama-server，尚未接入");
    }
}

package cn.edu.zjut.interview.speech.asr;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * provider 识别返回结果，对齐 {@code provider_types.rs::AsrResult} 的四个字段。
 *
 * <p>不在这里加时间戳 / 音频时长 / 分段：V1 没做识别链路，加了就是猜结构；
 * 等 V4 真的拿到 DashScope 响应再按实际字段扩，避免前端先按错的契约写死。
 *
 * <p><b>{@code provider_id} 必须是 snake_case</b>（V1 遗留契约 bug，V2 修正）：
 * 上游 {@code api/services/asr.ts::AsrResult} 读的是 {@code provider_id}。之前 Java record
 * 组件名 {@code providerId} 直接序列化成了 {@code providerId}，V3 搬前端时
 * {@code result.provider_id} 会是 {@code undefined}——识别成功但「用了哪个 provider」
 * 永远显示不出来，且不报错。record 组件名保持 {@code providerId}（Java 侧访问器是
 * {@code providerId()}，{@link cn.edu.zjut.interview.api.AsrController} 已经在用），
 * 只改 JSON 键名，两边都不受影响。
 *
 * @param text       识别出的文本
 * @param language   provider <strong>检测到</strong>的语言；拿不到时为 {@code null}
 *                   （原版注释明确：不要把入参的 languageHint 回填进来）
 * @param confidence provider 报告的置信度 0~1，可选；{@code null} 时不输出该键
 * @param providerId provider id，与 {@code listProviderInfo()} 一致
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AsrResult(
        @JsonProperty("text") String text,
        @JsonProperty("language") String language,
        @JsonProperty("confidence") Float confidence,
        @JsonProperty("provider_id") String providerId) {
}

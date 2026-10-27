package cn.edu.zjut.interview.speech.asr;

/**
 * provider 识别返回结果，对齐 {@code provider_types.rs::AsrResult} 的四个字段。
 *
 * <p>不在这里加时间戳 / 音频时长 / 分段：本批没做识别链路，加了就是猜结构；
 * 等 V4 真的拿到 DashScope 响应再按实际字段扩，避免前端先按错的契约写死。
 *
 * @param text       识别出的文本
 * @param language   provider <strong>检测到</strong>的语言；拿不到时为 {@code null}
 *                   （原版注释明确：不要把入参的 languageHint 回填进来）
 * @param confidence provider 报告的置信度 0~1，可选
 * @param providerId provider id，与 {@code listProviderInfo()} 一致
 */
public record AsrResult(String text, String language, Float confidence, String providerId) {
}

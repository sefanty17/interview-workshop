package cn.edu.zjut.interview.stream;

/**
 * 一个情绪段，对应 {@code processor.rs:23 EmotionSegment}。
 *
 * <p>Rust 侧还有 {@code confidence / voice_file / character / role_id}，都服务于 TTS 与多角色，
 * 本项目没有对应能力，故不带。{@code japaneseText} 按规格保留字段，但暂不消费。
 *
 * @param index          段序号，从 1 开始（与 {@code sentenceIndex} 无关）
 * @param originalTag    模型原样输出的情绪词
 * @param followingText  去掉 {@code <日语>} / {@code （动作）} / {@code 【标签】} 后的正文
 * @param motionText     动作文本，全角括号内
 * @param japaneseText   日语原文，没有则为空串
 * @param predicted      经 {@link EmotionDictionary} 折叠后的标准情绪
 */
public record EmotionSegment(int index,
                             String originalTag,
                             String followingText,
                             String motionText,
                             String japaneseText,
                             String predicted) {
}

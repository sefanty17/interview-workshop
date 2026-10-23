/**
 * F2：流式解析。
 *
 * <p>对应 {@code message_system/producer.rs} 与 {@code message_system/processor.rs}：
 * <ul>
 *   <li>{@link cn.edu.zjut.interview.stream.SentenceSplitter} —— 按 {@code 【} 标签边界流式分句 + 越界保护；</li>
 *   <li>{@link cn.edu.zjut.interview.stream.SymbolParser} —— 情绪/日语/动作结构化 + 用户消息预处理；</li>
 *   <li>{@link cn.edu.zjut.interview.stream.EmotionDictionary} —— {@code emotions.json} 别名折叠。</li>
 * </ul>
 */
package cn.edu.zjut.interview.stream;

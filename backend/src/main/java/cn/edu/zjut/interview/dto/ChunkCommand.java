package cn.edu.zjut.interview.dto;

/**
 * 一个待写入的片段：标题面包屑 + 正文 +（可选）假设问题。
 *
 * <p>序号 / 指纹 / 向量都由服务层算，调用方不需要知道这些实现细节。
 *
 * @param heading       标题面包屑（如「第2章 &gt; 2.1 内存泄漏」），可为 null
 * @param text          正文
 * @param hypeQuestions 假设问题（K6 HyPE），一行一个；只用于检索，不进提示词。可为 null
 */
public record ChunkCommand(
        String heading,
        String text,
        String hypeQuestions) {

    /** 不带假设问题的常见写法：老调用方（导入脚本的普通模式、单测）不用改。 */
    public ChunkCommand(String heading, String text) {
        this(heading, text, null);
    }
}

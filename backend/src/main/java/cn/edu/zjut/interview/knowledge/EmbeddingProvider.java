package cn.edu.zjut.interview.knowledge;

import java.util.List;

/**
 * 文本向量化抽象：把片段或查询转成同一空间的 float 向量，供余弦相似度检索。
 *
 * <p>抽成接口是为了让「本地假向量」和「真 embedding 服务」可以互换：
 * 演示/断网时用 {@link HashingEmbeddingProvider}，有 key 时换成
 * {@link OpenAiCompatibleEmbeddingProvider}，上层检索代码不用改。
 *
 * <p>换实现会让旧向量失去可比性，所以 {@link #modelName()} 与 {@link #dimension()}
 * 必须落库（{@code knowledge_vector.model/dim}），检索时成对过滤。
 */
public interface EmbeddingProvider {

    /** 模型标识，会写进 knowledge_vector.model。 */
    String modelName();

    /** 向量维度，会写进 knowledge_vector.dim。 */
    int dimension();

    /** 是否可用（没配 key / 模型缺失时 false，调用方据此降级）。 */
    boolean isAvailable();

    /** 单条嵌入；不可用时抛 IllegalStateException。 */
    float[] embed(String text);

    /** 批量嵌入，顺序与入参一一对应；空表返回空表。 */
    List<float[]> embedBatch(List<String> texts);
}

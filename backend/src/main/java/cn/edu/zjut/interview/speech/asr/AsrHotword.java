package cn.edu.zjut.interview.speech.asr;

/**
 * 一条热词，对齐 {@code provider_types.rs::Hotword}。
 *
 * <p>为什么要带 {@code weight} 而不是一串字符串：DashScope 的即时热词
 * （{@code parameters.vocabulary}）支持逐词权重，权重越高越容易被识别出来；
 * 只存词表会让 V4 接接口时又改一次数据结构。
 *
 * <p>{@code weight} 只对 DashScope 的即时热词有意义——本地 llama-asr 的 prompt 偏置
 * 与预编译热词都忽略它。
 *
 * @param text   热词原文
 * @param weight 权重，1..{@link #MAX_WEIGHT}
 */
public record AsrHotword(String text, int weight) {

    /** 默认权重：DashScope 文档推荐从 4 起测。 */
    public static final int DEFAULT_WEIGHT = 4;

    /** 权重上界：50 是文档里的「超级热词」特殊值（召回率大幅提升，最多 50 个）。 */
    public static final int MAX_WEIGHT = 50;

    public AsrHotword {
        weight = Math.max(1, Math.min(weight, MAX_WEIGHT));
    }

    /** 用默认权重构造。 */
    public static AsrHotword of(String text) {
        return new AsrHotword(text, DEFAULT_WEIGHT);
    }
}

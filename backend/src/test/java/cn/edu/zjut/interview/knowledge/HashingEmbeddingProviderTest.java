package cn.edu.zjut.interview.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 K1 §8 的 4 条断言：本地假向量的维度、归一化、确定性与「字面相近则更相似」。
 *
 * <p>纯单元测试，不起 Spring：这个实现没有任何外部依赖，起容器只会拖慢反馈。
 */
class HashingEmbeddingProviderTest {

    private final HashingEmbeddingProvider provider = new HashingEmbeddingProvider();

    /** 1. 维度固定 512，且单条嵌入的长度与 dimension() 一致（写库时 dim 列靠它）。 */
    @Test
    void 维度固定为512() {
        assertEquals(512, provider.dimension());
        assertEquals(512, provider.embed("任意").length);
        assertEquals("hashing-512", provider.modelName());
        assertTrue(provider.isAvailable());
    }

    /** 2. L2 范数为 1——归一化之后余弦相似度就是点积，检索侧不必再除范数。 */
    @Test
    void 向量已完成L2归一化() {
        float[] vector = provider.embed("JVM 内存泄漏怎么排查，还有 GC 调优");
        double sum = 0.0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        assertEquals(1.0, Math.sqrt(sum), 1e-4);
    }

    /** 3. 同一文本两次嵌入逐位相等：指纹没变就不重算向量的前提。 */
    @Test
    void 同一文本两次嵌入逐位相等() {
        String text = "垃圾回收器有 G1 和 ZGC，堆内存分新生代和老年代";
        assertArrayEquals(provider.embed(text), provider.embed(text));
    }

    /** 4. 字面重合多的句子余弦更高（假向量只认字面，不认语义）。 */
    @Test
    void 字面相近的句子相似度更高() {
        float[] base = provider.embed("JVM 内存泄漏怎么排查");
        float[] near = provider.embed("如何定位内存泄漏");
        float[] far = provider.embed("今天天气不错");

        double nearScore = cosine(base, near);
        double farScore = cosine(base, far);

        assertTrue(nearScore > farScore,
                "共享「内存泄漏」的句子应该更相似：near=" + nearScore + " far=" + farScore);
        assertTrue(nearScore > 0.3, "共享三个 bigram 时相似度不该这么低：" + nearScore);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
        }
        return dot;
    }
}

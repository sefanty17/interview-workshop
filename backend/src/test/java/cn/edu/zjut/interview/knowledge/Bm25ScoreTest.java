package cn.edu.zjut.interview.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 的分数符号回归测试：不需要 Spring 上下文，直接对手工构造的索引断言。
 *
 * <p>守的是一个具体的坑：idf 的 {@code N}（范围大小）与 {@code df}（词项出现片段数）
 * 必须是同一口径。曾经用「范围内 N + 全库 df」，当查询词在范围外更常见时
 * {@code N - n + 0.5} 变负、idf 变负，拿到的片段反而是负分——
 * 只搜 frontend 却问 java 术语（「内存」）时就会踩到。
 */
class Bm25ScoreTest {

    /** 三个片段都含「内存」，但检索范围只取第 0 条。 */
    private static RetrievalIndex 三条都含内存的索引() {
        List<RetrievalIndex.Doc> docs = List.of(
                new RetrievalIndex.Doc(1L, 1L, "d0", "文档0", "第1章", "内存分为堆和栈"),
                new RetrievalIndex.Doc(2L, 2L, "d1", "文档1", "第1章", "内存泄漏怎么查"),
                new RetrievalIndex.Doc(3L, 3L, "d2", "文档2", "第1章", "内存溢出与调优"));
        return new RetrievalIndex(docs, new float[docs.size()][], 4);
    }

    @Test
    void 范围外更常见的词项在范围内仍然得正分() {
        RetrievalIndex index = 三条都含内存的索引();

        // N=1（范围只有第 0 条），全库 df=3。老写法会算出 ln(0.57) < 0。
        Map<Integer, Double> scores = RetrievalService.bm25Scores(index, List.of(0), "内存");

        assertEquals(1, scores.size());
        assertTrue(scores.get(0) > 0.0, "idf 必须是正的，实际 " + scores.get(0));
    }

    @Test
    void 一个查询词都没命中的片段不进候选() {
        RetrievalIndex index = 三条都含内存的索引();

        Map<Integer, Double> scores = RetrievalService.bm25Scores(index, List.of(0, 1, 2), "完全无关的天气话题");

        assertTrue(scores.isEmpty(), "零分片段不该占候选池，实际 " + scores);
    }

    @Test
    void 命中词越集中得分越高() {
        List<RetrievalIndex.Doc> docs = List.of(
                new RetrievalIndex.Doc(1L, 1L, "d0", "文档0", "第1章", "垃圾回收器 G1 和 ZGC 的区别"),
                new RetrievalIndex.Doc(2L, 2L, "d1", "文档1", "第2章", "顺带提一句收器的实现细节"));
        RetrievalIndex index = new RetrievalIndex(docs, new float[docs.size()][], 4);

        Map<Integer, Double> scores = RetrievalService.bm25Scores(index, List.of(0, 1), "垃圾回收器");

        assertEquals(2, scores.size(), "两条都至少命中一个词项，实际 " + scores);
        assertTrue(scores.get(0) > scores.get(1), "命中多个词项的片段必须高于只命中单个词项的片段");
    }
}

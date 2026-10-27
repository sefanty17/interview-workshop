package cn.edu.zjut.interview.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变检索快照：一份片段列表 + 预先算好的 BM25 统计量 + 与片段同序的向量矩阵。
 *
 * <p>构建一次、之后只读，所以可以安全地被多线程共享（{@link RetrievalService} 用它做缓存）。
 * 片段顺序固定为 {@code chunk_id} 升序，排序打平时的「chunk_id 升序」直接退化成下标比较。
 *
 * <p>统计量口径（勿随意改，BM25 的分数与阈值都依赖它）：
 * <ul>
 *   <li>{@code termFreqs} 是<b>单条片段内</b>的词频；</li>
 *   <li>{@code docFreq} 是<b>全库</b>范围内出现过该词项的片段数；</li>
 *   <li>{@code avgLength} 按词项总数算，不是字符数；标题与假设问题都计入（它们同样进词表）。</li>
 * </ul>
 */
public final class RetrievalIndex {

    /**
     * 一条片段在索引里的样子：检索结果只需要这些字段，不把实体暴露出去。
     *
     * <p>{@code hype} 是 K6 的假设问题，<b>只进词表与向量，不进检索结果</b>
     * （结果里的 text 要原样拼提示词，掺进模型生成的问题会让面试官念错东西）。
     */
    public record Doc(long chunkId, long docId, String docCode, String docTitle,
                      String heading, String text, String hype) {
    }

    private final List<Doc> docs;
    /** 每条片段的词项序列（保留重复项），顺序与 {@link #docs} 一致。 */
    private final List<List<String>> terms;
    /** 每条片段的 tf 表，顺序与 {@link #docs} 一致。 */
    private final List<Map<String, Integer>> termFreqs;
    /** 词项 -> 出现过它的片段数（全库口径）。 */
    private final Map<String, Integer> docFreq;
    /** 平均词项数；片段数为 0 时取 1.0，避免 BM25 里除零。 */
    private final double avgLength;
    /** 与 {@link #docs} 同序；取不到向量的位置为 null（跨模型、缺行都算取不到）。 */
    private final float[][] vectors;
    private final int dim;

    /**
     * @param docs    片段，调用方保证已按 {@code chunk_id} 升序且只含启用文档
     * @param vectors 与 docs 同序的向量，允许元素为 null
     * @param dim     向量维度（来自当前 embedding provider）
     */
    RetrievalIndex(List<Doc> docs, float[][] vectors, int dim) {
        List<Doc> docCopy = List.copyOf(docs);
        List<List<String>> termCopy = new ArrayList<>(docCopy.size());
        List<Map<String, Integer>> freqCopy = new ArrayList<>(docCopy.size());
        Map<String, Integer> df = new LinkedHashMap<>();
        long totalTerms = 0;
        for (Doc doc : docCopy) {
            // 标题也进词表：「3.2 内存泄漏排查」这种面包屑是很有用的检索信号。
            // 假设问题（K6）同样进词表：它的存在意义就是覆盖「用户自己的说法」，
            // 不参与打分的话 HyPE 等于没做。
            String indexText = (doc.heading() == null ? "" : doc.heading() + " ")
                    + doc.text()
                    + (doc.hype() == null || doc.hype().isBlank() ? "" : " " + doc.hype());
            List<String> docTerms = List.copyOf(ChineseTokenizer.tokenize(indexText));
            termCopy.add(docTerms);
            totalTerms += docTerms.size();

            Map<String, Integer> tf = new LinkedHashMap<>();
            for (String term : docTerms) {
                tf.merge(term, 1, Integer::sum);
            }
            freqCopy.add(Map.copyOf(tf));
            for (String term : tf.keySet()) {
                df.merge(term, 1, Integer::sum);
            }
        }
        this.docs = docCopy;
        this.terms = List.copyOf(termCopy);
        this.termFreqs = List.copyOf(freqCopy);
        this.docFreq = Collections.unmodifiableMap(df);
        this.avgLength = docCopy.isEmpty() ? 1.0 : (double) totalTerms / docCopy.size();
        this.vectors = vectors == null ? new float[docCopy.size()][] : vectors;
        this.dim = dim;
    }

    public int size() {
        return docs.size();
    }

    public List<Doc> docs() {
        return docs;
    }

    /** 第 i 条片段的词项序列（含重复项）。 */
    public List<String> termsOf(int i) {
        return terms.get(i);
    }

    /** 第 i 条片段里 term 出现的次数；没出现返回 0。 */
    public int termFreq(int i, String term) {
        Integer count = termFreqs.get(i).get(term);
        return count == null ? 0 : count;
    }

    /** 全库范围内出现过 term 的片段数。 */
    public int docFreq(String term) {
        Integer count = docFreq.get(term);
        return count == null ? 0 : count;
    }

    public double avgLength() {
        return avgLength;
    }

    /** 第 i 条片段的向量；没有可用向量时返回 null。 */
    public float[] vectorAt(int i) {
        return vectors[i];
    }

    public int dim() {
        return dim;
    }
}

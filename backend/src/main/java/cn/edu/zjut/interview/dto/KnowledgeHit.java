package cn.edu.zjut.interview.dto;

/**
 * 一条检索命中：片段的展示字段 + 三个分数。
 *
 * <p>分数保留原始值而不是只给融合分，是为了运维/评测能看清「这条是靠哪一路召回的」：
 * {@code vectorScore} 是原始余弦，{@code bm25Score} 是原始 BM25，{@code score} 才是 RRF 融合分。
 * 没被某一路召回时该路分数为 0。
 */
public record KnowledgeHit(
        long chunkId,
        long docId,
        String docCode,
        String docTitle,
        String heading,
        String text,
        double score,
        double vectorScore,
        double bm25Score) {
}

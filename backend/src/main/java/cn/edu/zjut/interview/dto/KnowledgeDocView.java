package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.KnowledgeDoc;

import java.time.LocalDateTime;

/**
 * 岗位知识文档视图（K1）：元信息 + 派生统计。
 *
 * <p>{@code chunkCount}/{@code vectorCount} 不落库，是查的时候数出来的：
 * 两者不一致就说明「切了片段但向量没算完」（导入中断 / 换了模型没重建），
 * 前端据此提示重建。
 *
 * <p>{@code model}/{@code dim} 取该文档第一条向量的值，没有向量时 model 为 null、dim 为 0。
 */
public record KnowledgeDocView(
        String code,
        String title,
        String category,
        String sourcePath,
        String description,
        boolean enabled,
        long chunkCount,
        long vectorCount,
        String model,
        int dim,
        LocalDateTime updatedAt) {

    public static KnowledgeDocView of(KnowledgeDoc doc,
                                      long chunkCount,
                                      long vectorCount,
                                      String model,
                                      int dim) {
        return new KnowledgeDocView(
                doc.getCode(),
                doc.getTitle(),
                doc.getCategory(),
                doc.getSourcePath(),
                doc.getDescription(),
                Boolean.TRUE.equals(doc.getEnabled()),
                chunkCount,
                vectorCount,
                model,
                dim,
                doc.getUpdatedAt());
    }
}

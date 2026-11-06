package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.KnowledgeChunk;

/**
 * 知识库片段视图：切块之后的「位置 + 内容」。
 *
 * <p>{@code chunkIndex} 是文档内从 0 开始的序号，检索命中与后端日志都用它定位；
 * 前端「切块预览」据此展示导入后可检索的最小单位。
 */
public record KnowledgeChunkView(
        int chunkIndex,
        String heading,
        String text,
        int charCount) {

    public static KnowledgeChunkView of(KnowledgeChunk chunk) {
        return new KnowledgeChunkView(
                chunk.getChunkIndex() == null ? -1 : chunk.getChunkIndex(),
                chunk.getHeading(),
                chunk.getText(),
                chunk.getCharCount() == null ? 0 : chunk.getCharCount());
    }
}

package cn.edu.zjut.interview.dto;

/**
 * 一个待写入的片段（K1）：只有标题面包屑与正文，序号/指纹/向量都由服务层算。
 *
 * <p>调用方（导入脚本、接口）不需要知道 chunk_index 从 0 递增这类实现细节。
 */
public record ChunkCommand(
        String heading,
        String text) {
}

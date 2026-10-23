package cn.edu.zjut.interview.agent;

/**
 * 剧本包 {@code .agent/} 下的一份流程产物（设计稿、任务队列、用户约束……），
 * 对齐原版 {@code AgentArtifact}。
 */
public record AgentArtifact(
        /** 文件名（含 {@code .md}）。 */
        String name,
        /** 文件全文；超过 64 KB 的文件只列名字不列内容（原版 {@code ARTIFACT_MAX_BYTES}）。 */
        String content) {
}

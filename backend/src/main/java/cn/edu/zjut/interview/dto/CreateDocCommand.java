package cn.edu.zjut.interview.dto;

/**
 * 建文档命令（K1）：接口请求体与服务入参共用一个形状，避免控制器里再抄一遍字段。
 *
 * <p>{@code code} 由调用方指定而不是自增数字，因为导入脚本要靠它做幂等 upsert。
 */
public record CreateDocCommand(
        String code,
        String title,
        String category,
        String sourcePath,
        String description) {
}

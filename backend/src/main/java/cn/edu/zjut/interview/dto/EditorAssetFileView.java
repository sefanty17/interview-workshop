package cn.edu.zjut.interview.dto;

/**
 * 单条背景资源（规格 E6a §8，前端 {@code AssetFileWeb}）。
 *
 * @param name 文件名（如 {@code night.webp}）
 * @param url  前端可直接访问的 HTTP 路径（如 {@code /backgrounds/night.webp}）
 * @param size 文件字节数
 */
public record EditorAssetFileView(String name, String url, long size) {
}

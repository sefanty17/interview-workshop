package cn.edu.zjut.interview.dto;

import java.util.List;

/**
 * 素材索引，对齐原版 {@code Assets/} 下的五类子目录。
 *
 * <p>本项目导入器只搬背景图（见 {@code ScriptYamlImporter.publishAssets}），
 * 因此 {@code music} / {@code sound} / {@code ambient} 恒为空 list，{@code pic} 与 {@code background} 共用一份。
 */
public record EditorAssetIndex(
        List<String> background,
        List<String> music,
        List<String> sound,
        List<String> ambient,
        List<String> pic) {
}

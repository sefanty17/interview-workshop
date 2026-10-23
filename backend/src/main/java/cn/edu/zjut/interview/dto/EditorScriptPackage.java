package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 编辑器视角的一个剧本包，对齐原版 {@code ScriptPackage}。
 *
 * <p>技术栈差异：原版有 {@code standalone/} 与 {@code character/<角色>/} 两种磁盘布局，
 * 本项目剧本一律存在 {@code interview_script} 一行里，没有这两种布局之分，
 * 因此 {@code layout} 恒为 {@code "standalone"}、{@code boundCharacterFolder} 恒为 {@code null}。
 *
 * <p>{@code isAdventure} 刻意声明成装箱类型 {@link Boolean}：record 的 {@code boolean} 分量名以
 * {@code is} 开头时 Jackson 会把 JSON 键压成 {@code adventure}，前端读 {@code p.isAdventure} 就是 undefined。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EditorScriptPackage(
        String key,
        String layout,
        String folderName,
        String boundCharacterFolder,
        String scriptName,
        String description,
        Boolean isAdventure,
        int chapterCount,
        boolean loadedByEngine) {
}

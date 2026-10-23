package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 剧本详情：包信息 + 剧本设置 + 章节列表 + 素材索引 + 角色列表。
 *
 * <p>{@code characters} 本批恒为空 list（E3 CharactersTab 时补）。
 */
public record EditorScriptDetail(
        // JSON 键必须是 package（对齐原版 ScriptDetail.package），pkg 只是 Java 侧的规避
        @JsonProperty("package") EditorScriptPackage pkg,
        Map<String, Object> storyConfig,
        List<EditorChapterSummary> chapters,
        EditorAssetIndex assets,
        List<Map<String, Object>> characters) {
}

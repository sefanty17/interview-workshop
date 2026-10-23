package cn.edu.zjut.interview.dto.character;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * {@code PUT /api/editor/characters/{folder}} 的请求体（规格 R1a §4）。
 *
 * <p>{@code rawYaml} 与 {@code fields} 二选一：
 * <ul>
 *   <li>给 {@code rawYaml}：整份覆盖（角色 Tab 直接编辑文本框的那一档）；</li>
 *   <li>给 {@code fields}：按字段覆盖（后端 dump 成 yml 落盘）。</li>
 * </ul>
 * 两个都给以 {@code rawYaml} 为准；两个都不给 → 400。
 *
 * <p>{@code folder} 只是冗余字段：路径里的 folder 才是权威，这里给的不一致时以路径为准
 * （原版没有这个字段，是为了让「新建后改名」类的调用体自解释才留的）。
 */
public record SaveCharacterRequest(
        @JsonProperty("folder") String folder,
        @JsonProperty("rawYaml") String rawYaml,
        @JsonProperty("fields") Map<String, Object> fields) {
}

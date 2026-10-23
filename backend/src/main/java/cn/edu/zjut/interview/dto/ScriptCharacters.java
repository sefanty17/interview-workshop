package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code interview_script.characters} 的读写：库里是 JSON 数组字符串（如 {@code ["MAIN"]}），
 * 接口对外给 {@code List<String>}。
 *
 * <p>对齐 LingChat 剧本目录下的 {@code characters/} 子目录名集合；
 * 解析不了（空值 / 非数组 / 坏 JSON）一律当空列表，不让脏数据把接口打成 500。
 */
public final class ScriptCharacters {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScriptCharacters() {
    }

    /** JSON 数组字符串 → 角色名列表；无法解析返回空列表。 */
    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    names.add(item.asText());
                }
            }
            return List.copyOf(names);
        } catch (Exception e) {
            return List.of();
        }
    }
}

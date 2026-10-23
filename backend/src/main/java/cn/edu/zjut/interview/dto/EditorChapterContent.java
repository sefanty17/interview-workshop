package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 章节内容。{@code events} 是解析后的 JSON 数组（不是字符串）。 */
public record EditorChapterContent(
        String id,
        String name,
        List<JsonNode> events) {
}

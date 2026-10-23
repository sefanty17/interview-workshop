package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 请求体。原版的 {@code extra}（章节 YAML 其余顶层键）本项目无对应列，忽略之。 */
public record EditorWriteChapterRequest(
        String name,
        List<JsonNode> events) {
}

package cn.edu.zjut.interview.dto;

import java.util.Map;

/** 剧本设置页保存。只接受白名单键，其余忽略。 */
public record EditorWriteConfigRequest(
        Map<String, Object> config) {
}

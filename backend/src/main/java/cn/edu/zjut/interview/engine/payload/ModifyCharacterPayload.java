package cn.edu.zjut.interview.engine.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code script:modify-character} 的 data，对应
 * {@code script_engine/responses.rs::ModifyCharacterPayload}。
 *
 * <p>原版还有 {@code clothes} / {@code duration} 两个字段：本项目没有立绘换装，
 * 这两个字段不迁。
 *
 * @param characterId  说话角色标识（{@code MAIN} 已解析成面试官 code）
 * @param emotion      情绪；没写就是 null，不序列化
 * @param action       {@code show_character} / {@code hide_character}；没写就是 null
 * @param presentRoles 变更之后的在场角色，供前端渲染多槽位
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModifyCharacterPayload(
        String characterId,
        String emotion,
        String action,
        List<String> presentRoles) {
}

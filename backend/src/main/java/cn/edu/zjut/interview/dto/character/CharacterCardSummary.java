package cn.edu.zjut.interview.dto.character;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 角色卡的摘要（规格 R1a §4），对应 {@code GET /api/editor/characters} 的一个元素。
 *
 * <p>字段名一律用 JSON camelCase（{@code ai_name} → {@code aiName}），与原版 settings.yml 里的
 * snake_case 键区分开：yml 的键名属于磁盘格式，DTO 的键名属于接口契约，两边不再互相绑架。
 * 这里仍然显式写 {@code @JsonProperty}（项目已有 {@code @JsonProperty("package")} 的先例），
 * 免得 Jackson 的命名策略哪天变了接口跟着变。
 */
public record CharacterCardSummary(

        /** 目录名，也是角色标识（原版 folder）。 */
        @JsonProperty("folder") String folder,

        /** {@code ai_name}：角色自己叫什么。 */
        @JsonProperty("aiName") String aiName,

        /** {@code ai_subtitle}：角色名下的小字。 */
        @JsonProperty("aiSubtitle") String aiSubtitle,

        /** {@code user_name}：角色怎么称呼用户。 */
        @JsonProperty("userName") String userName,

        /** {@code user_subtitle}：用户名下的小字。 */
        @JsonProperty("userSubtitle") String userSubtitle,

        /** {@code title}：卡面标题。 */
        @JsonProperty("title") String title,

        /** {@code avatar_mode}：{@code live2d} / {@code image}。 */
        @JsonProperty("avatarMode") String avatarMode) {
}

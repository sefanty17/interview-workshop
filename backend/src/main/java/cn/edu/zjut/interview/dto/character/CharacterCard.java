package cn.edu.zjut.interview.dto.character;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 角色卡详情（规格 R1a §4），对应 {@code GET /api/editor/characters/{folder}}。
 *
 * <p>比 {@link CharacterCardSummary} 多出的三类内容：
 * <ul>
 *   <li>{@code info / scale / offsetX / offsetY / bubbleTop / bubbleLeft}：卡面与立绘摆放参数；</li>
 *   <li>{@code voiceModels / bodyPart / clothes}：原样吐回的结构化块，前端整块编辑、整块存回，
 *       后端不理解其内部结构（原版也是整块读写）；</li>
 *   <li>{@code rawYaml}：把解析出的整份 yml 重新 dump 成的文本，供角色 Tab 原样编辑/保存。</li>
 * </ul>
 */
public record CharacterCard(

        @JsonProperty("folder") String folder,
        @JsonProperty("aiName") String aiName,
        @JsonProperty("aiSubtitle") String aiSubtitle,
        @JsonProperty("userName") String userName,
        @JsonProperty("userSubtitle") String userSubtitle,
        @JsonProperty("title") String title,
        @JsonProperty("avatarMode") String avatarMode,

        /** {@code info}：卡面简介，多段文本。 */
        @JsonProperty("info") String info,

        /** {@code scale}：立绘缩放。 */
        @JsonProperty("scale") Double scale,

        /** {@code offset_x}：立绘横向偏移。 */
        @JsonProperty("offsetX") Double offsetX,

        /** {@code offset_y}：立绘纵向偏移。 */
        @JsonProperty("offsetY") Double offsetY,

        /** {@code bubble_top}：气泡距顶百分比。 */
        @JsonProperty("bubbleTop") Integer bubbleTop,

        /** {@code bubble_left}：气泡距左百分比。 */
        @JsonProperty("bubbleLeft") Integer bubbleLeft,

        /** {@code voice_models}：语音模型参数，原样透传。 */
        @JsonProperty("voiceModels") Map<String, Object> voiceModels,

        /** {@code body_part}：可点击部位与多边形热区，原样透传。 */
        @JsonProperty("bodyPart") Object bodyPart,

        /** {@code clothes}：服装列表，原样透传。 */
        @JsonProperty("clothes") Object clothes,

        /** 整份 settings.yml 重新 dump 出的文本（保留中文，不转义）。 */
        @JsonProperty("rawYaml") String rawYaml) {
}

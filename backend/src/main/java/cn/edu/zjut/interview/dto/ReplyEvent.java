package cn.edu.zjut.interview.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code ai:reply} 事件的 data，字段名与规格 B3-BE §1.2 一字不差（camelCase，一个不少）。
 *
 * @param type            固定 {@code "reply"}
 * @param isFinal         是否为本轮收尾句
 * @param duration        展示时长（秒），{@code max(1.2, 字数 * 0.08)} 保留 1 位小数
 * @param message         已剥离 {@code 【情绪】} / {@code （动作）} / {@code <日语>} 的正文
 * @param originalMessage 分句器投递的原文（含标签）
 * @param emotion         折叠到 20 枚举后的情绪
 * @param originalTag     模型原样输出的情绪词
 * @param motionText      动作文本（全角括号内），没有则空串
 * @param character       角色编码名
 * @param displayName     展示名
 * @param displaySubtitle 展示副标题
 * @param userMessageSeq  触发本轮的用户消息 seq
 * @param senderRoleId    说话角色：null = 旁白 / system，其余为 {@code interviewer.id}
 *                        （对齐 {@code line.sender_role_id}）
 * @param seq             本句台词的落库 seq，前端用它把 {@code ai:voice} 的语音对上这一句
 *                        （与 {@link VoiceEvent#seq()} 同源）；剧本链路本批暂为 null
 */
@JsonPropertyOrder({"type", "isFinal", "duration", "message", "originalMessage", "emotion",
        "originalTag", "motionText", "character", "displayName", "displaySubtitle", "userMessageSeq",
        "senderRoleId", "seq"})
public record ReplyEvent(
        String type,
        @JsonProperty("isFinal") boolean isFinal,
        double duration,
        String message,
        String originalMessage,
        String emotion,
        String originalTag,
        String motionText,
        String character,
        String displayName,
        String displaySubtitle,
        Integer userMessageSeq,
        Integer senderRoleId,
        Integer seq) {

    /** 事件名，规格 §1.2 表格。 */
    public static final String TYPE = "reply";
}

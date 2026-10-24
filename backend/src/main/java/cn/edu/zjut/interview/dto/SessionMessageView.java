package cn.edu.zjut.interview.dto;

import cn.edu.zjut.interview.domain.InterviewMessage;

/**
 * {@code GET /api/sessions/{id}/messages} 的单条，字段即规格 §1.3 的返回列。
 *
 * @param seq         场次内序号
 * @param role        system / user / assistant
 * @param content     正文
 * @param emotion     折叠后的情绪（system/user 行为 null）
 * @param action      动作文本
 * @param originalTag 模型原样输出的情绪词
 * @param senderRoleId 说话角色：0 = 玩家、N = 角色 id、null = 旁白 / system
 *                     （对齐 {@code line.sender_role_id}）
 * @param thinking     本条回复的思考链（对齐 {@code line.thinking}）；只有本轮最终句有，其余为 null
 * @param identityId   玩家说的是哪个身份（对齐 {@code line.identity_id}），非玩家行为 null
 * @param displayName  这一句在历史里显示成谁说的（对齐 {@code line.display_name}）；
 *                     玩家行必填，旁白 / system 行留 null
 * @param userMessageSeq 这是本场第几条用户消息（1-based，只对 {@code role=user} 有值）。
 *                      对齐 {@code GameMessage.user_message_seq}：历史页的「回溯」按钮靠它
 *                      定位截断到哪一条。不加物理列 —— 按 seq 顺序现算，避免与落库时序不一致。
 */
public record SessionMessageView(
        Integer seq,
        String role,
        String content,
        String emotion,
        String action,
        String originalTag,
        Integer senderRoleId,
        String thinking,
        String identityId,
        String displayName,
        Integer userMessageSeq) {

    public static SessionMessageView of(InterviewMessage message) {
        return new SessionMessageView(
                message.getSeq(),
                message.getRole(),
                message.getContent(),
                message.getPredictedEmotion(),
                message.getAction(),
                message.getOriginalTag(),
                message.getSenderRoleId(),
                message.getThinking(),
                message.getIdentityId(),
                message.getDisplayName(),
                null);
    }

    /** 只改 {@code userMessageSeq} 的副本（{@code of} 之后由控制器统一编号）。 */
    public SessionMessageView withUserMessageSeq(Integer userMessageSeq) {
        return new SessionMessageView(seq, role, content, emotion, action, originalTag,
                senderRoleId, thinking, identityId, displayName, userMessageSeq);
    }
}

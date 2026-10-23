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
 */
public record SessionMessageView(
        Integer seq,
        String role,
        String content,
        String emotion,
        String action,
        String originalTag,
        Integer senderRoleId) {

    public static SessionMessageView of(InterviewMessage message) {
        return new SessionMessageView(
                message.getSeq(),
                message.getRole(),
                message.getContent(),
                message.getPredictedEmotion(),
                message.getAction(),
                message.getOriginalTag(),
                message.getSenderRoleId());
    }
}

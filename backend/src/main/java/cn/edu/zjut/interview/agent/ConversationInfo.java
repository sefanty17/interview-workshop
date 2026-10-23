package cn.edu.zjut.interview.agent;

import java.time.LocalDateTime;

/**
 * 会话信息（对齐原版 {@code ConversationInfo}）。
 *
 * <p>时间用 {@link LocalDateTime}（Jackson 输出 ISO-8601，如 {@code 2026-10-23T10:00:00.123}）；
 * 原版是 Rust {@code NaiveDateTime} 的 Display（空格分隔），前端只用于展示，故不逐字照搬。
 */
public record ConversationInfo(
        Long id,
        String title,
        /** 创建会话时绑定的剧本 key（可为 null）。 */
        String scriptKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

package cn.edu.zjut.interview.dto;

/**
 * 试玩场次视图（规格 E5b §1.3），对应原版 {@code editor_start_preview} 返回的
 * {@code PreviewSession}（但那边的字段是 {@code line_len / generation / scene} 快照）。
 *
 * <p>本项目没有全局共享会话状态，「快照/还原」退化成「独立试玩场次 + 退出即删」，
 * 所以这里只需要交出「前端要播哪一场、用哪个人设演」的最小信息，不照抄原版快照字段。
 *
 * @param sessionId       试玩场次 id（{@code interview_session.id}），后续沙盘推进都带它
 * @param scriptCode      剧本编码（{@code interview_script.code}）
 * @param fromChapter     起始章节 id；为 null 时表示从 {@code intro_chapter} 开始
 * @param interviewerCode 剧本绑定的（或兜底选出的）面试官编码，试玩舞台按它解析人设
 * @param interviewerName 面试官显示名，试玩顶栏 label 用
 * @param jobCode         岗位编码，建场次时传给 {@code ChatService}
 */
public record PreviewSessionView(Long sessionId,
                                 String scriptCode,
                                 String fromChapter,
                                 String interviewerCode,
                                 String interviewerName,
                                 String jobCode) {
}

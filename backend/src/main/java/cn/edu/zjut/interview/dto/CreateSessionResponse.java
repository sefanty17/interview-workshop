package cn.edu.zjut.interview.dto;

/**
 * {@code POST /api/sessions} 的响应体（规格 B3-BE §1.1）。
 *
 * @param sessionId          新场次 id
 * @param interviewer        角色展示信息
 * @param opening            开场白（已解析情绪与正文）
 * @param systemPromptLength 本次落库的 system 串长度（联调时用来确认拼装成功）
 */
public record CreateSessionResponse(
        Long sessionId,
        InterviewerView interviewer,
        OpeningView opening,
        int systemPromptLength) {

    /** 角色展示信息，字段与规格一致。 */
    public record InterviewerView(String code, String name, String displayRole, String emotion) {
    }

    /** 开场白；{@code seq} 是它在 {@code interview_message} 里的序号（固定 1）。 */
    public record OpeningView(String message, String emotion, int seq) {
    }
}

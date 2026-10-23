package cn.edu.zjut.interview.dto;

/**
 * {@code POST /api/sessions} 的请求体（规格 B3-BE §1.1，规格 F-1 §B6）。
 *
 * @param interviewerCode 角色卡编码，对应 {@code interviewer.code}
 * @param jobId           岗位编码，对应 {@code interview_script.job_code}
 * @param scriptCode      剧本编码，对应 {@code interview_script.code}；为 null/空白时
 *                        不绑剧本（纯自由对话），要演剧本走 {@code POST /{id}/script/start}
 */
public record CreateSessionRequest(String interviewerCode, String jobId, String scriptCode) {
}

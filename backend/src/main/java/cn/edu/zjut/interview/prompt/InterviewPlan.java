package cn.edu.zjut.interview.prompt;

import java.util.List;

/**
 * 【本项目扩展】面试编排段的数据载体。
 *
 * <p>LingChat 没有「面试流程」的概念（它只有角色卡 + 剧本旁白），这一段是本项目为了
 * 让模型知道「这场面试问到哪、按什么节奏、什么条件下结束」而新增的，模板见规格
 * B0-BE §3.3。除了本段之外，{@link PromptAssembler} 的其余拼装逻辑全部照抄
 * {@code prompt.rs:183 sys_prompt_builder}。
 *
 * <p>数据来源：{@code interview_script}（岗位 / 面试官 / 轮次控制 / 结束条件）+
 * {@code interview_chapter}（章节名 / 目标）。脚本设置里的这两个字段由 F3 的场次
 * 初始化逻辑填进来，本批只负责按模板渲染。
 *
 * @param job             岗位，对应 {@code interview_script.job_code}
 * @param interviewerName 面试官名，对应 {@code interviewer.name}
 * @param displayRole     面试官展示头衔，对应 {@code interviewer.display_role}
 * @param stages          流程阶段（按 {@code order_num} 排好序），对应 {@code interview_chapter}
 * @param pacing          轮次控制文案
 * @param endCondition    结束条件文案
 */
public record InterviewPlan(
        String job,
        String interviewerName,
        String displayRole,
        List<Stage> stages,
        String pacing,
        String endCondition) {

    /**
     * 一个流程阶段。
     *
     * @param name 章节名，对应 {@code interview_chapter.name}
     * @param goal 该阶段目标
     */
    public record Stage(String name, String goal) {
    }

    /**
     * 渲染成「本次面试编排」段。段末带一个 {@code \n}，
     * 供 {@link PromptAssembler} 直接追加（后面紧跟框架句自带的前导 {@code \n}）。
     */
    public String toSegment() {
        StringBuilder sb = new StringBuilder();
        sb.append("【本次面试编排】\n");
        sb.append("岗位：").append(nullToEmpty(job)).append('\n');
        sb.append("面试官：").append(nullToEmpty(interviewerName))
                .append('（').append(nullToEmpty(displayRole)).append("）\n");
        sb.append("流程：\n");
        int index = 1;
        for (Stage stage : stages) {
            sb.append(index++).append(". ").append(nullToEmpty(stage.name()))
                    .append(" —— ").append(nullToEmpty(stage.goal())).append('\n');
        }
        sb.append("轮次控制：").append(nullToEmpty(pacing)).append('\n');
        sb.append("结束条件：").append(nullToEmpty(endCondition)).append('\n');
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

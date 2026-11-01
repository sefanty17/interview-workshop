package cn.edu.zjut.interview.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一场面试的对话总结（<b>本项目扩展（issue #4）</b>）。
 *
 * <p>中立口径：只记录事实，不打分、不评价好坏，与 {@link EvaluationResult} 的面试官口径刻意分开。
 * 报告页同时展示两者，读者一眼能分清「他问了什么、我答了什么」与「这次答得怎么样」。
 *
 * @param status    生成状态：{@code ok} / {@code failed}
 * @param overview  一段客观概述（对话里发生了什么）；失败时为空串
 * @param error     失败原因；成功时为 null
 */
public record SummaryResult(Long sessionId,
                            String status,
                            String overview,
                            List<QuestionNote> questions,
                            Coverage coverage,
                            Vocabulary vocabulary,
                            String model,
                            LocalDateTime createdAt,
                            String error) {

    /**
     * 单轮记录。
     *
     * @param index    题号（1-based）
     * @param question 面试官问了什么（概括，不照抄整段台词）
     * @param points   求职者给出的核心论点
     * @param blockers 卡壳点（没答上来 / 答错 / 含糊带过的地方）
     * @param depth    作答深度标记：{@code 深} / {@code 中} / {@code 浅}
     */
    public record QuestionNote(int index,
                               String question,
                               List<String> points,
                               List<String> blockers,
                               String depth) {
    }

    /**
     * 知识点覆盖。
     *
     * @param covered 对话里聊到并答到的知识点
     * @param missing 岗位要求里明显没覆盖到的知识点
     */
    public record Coverage(List<String> covered, List<String> missing) {
    }

    /**
     * 用词记录。
     *
     * @param good    回答里用得准确的术语
     * @param improve 表达上可以更准确 / 更专业的说法
     */
    public record Vocabulary(List<String> good, List<String> improve) {
    }
}

package cn.edu.zjut.interview.summary;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 对话总结 agent 的 prompt 组装（纯静态、可单测，<b>本项目扩展（issue #4）</b>）。
 *
 * <p>与 {@code EvaluationPrompter} 的关键区别：这边是<b>中立整理员</b>，不是面试官。
 * 如果复用面试官人设，模型会本能地带入「评判」口吻（「你答得不够深」），
 * 与「总结只陈述事实」的产品定位冲突，也会和报告页另一份评估结论重复。
 *
 * <p>长对话分两段处理：
 * <ol>
 *   <li>{@link #buildChunk} —— 每次只喂一个片段，让模型先按片段抽取逐轮事实；</li>
 *   <li>{@link #buildMerge} —— 把片段结果当输入，合并成一份完整摘要。</li>
 * </ol>
 * 短对话（总量在 {@link #MAX_CHUNK_CHARS} 以内）直接一次 {@link #buildFinal}，不做无谓的两次调用。
 */
public final class SummaryPrompter {

    /** 中立角色设定：只整理事实，不打分、不评价。 */
    private static final String ROLE = "你是一名中立的面试记录整理员，不属于任何面试官角色。"
            + "你的任务不是打分、不是批评、也不是安慰，而是把一场模拟面试的对话整理成结构化的事实摘要，"
            + "供求职者事后复盘。所有描述都要以对话内容为唯一依据。";

    /** 输出格式硬约束（同评估线：模型最爱顺手加围栏和开场白，得先立护栏）。 */
    private static final String OUTPUT_RULE =
            "只输出 JSON，不要 Markdown 围栏、不要任何解释文字。";

    /** 中立口径：最容易跑偏的是把「事实」写成「评价」，所以单独强调。 */
    private static final String NEUTRAL_RULE = """
            中立要求：
            - overview 只陈述客观事实（问了哪些方向、答到什么程度），不使用「优秀 / 很差 / 可惜」这类评价词。
            - 不要编造对话里没有的内容；对话里没提到的知识点不要写进 covered。
            - 求职者没说清楚的地方写进 blockers，说清楚的地方写进 points，不要替对方补充论据。""";

    /** 单轮记录的口径。 */
    private static final String QUESTION_RULE = """
            逐轮记录要求：
            - questions 按对话顺序一条一轮；题号 index 从 1 开始连续编号。
            - question 概括面试官这一轮问什么（不超过 30 字），不要照抄整段台词。
            - points 是求职者给出的核心论点（0-3 条，每条不超过 20 字）；没有实质论点就留空数组。
            - blockers 是卡壳点：没答上来、答错、含糊带过的地方（0-3 条，每条不超过 20 字）。
            - depth 只能是「深」「中」「浅」之一：深 = 讲到了原理与取舍；中 = 说清了做法但没讲取舍；浅 = 只有结论或背诵定义。""";

    /** 完整摘要的 JSON 契约，与 {@link SummaryParser} 的解析口径一一对应。 */
    private static final String FINAL_TEMPLATE = """
            {
              "overview": "一段客观概述（不超过 60 字）",
              "questions": [
                {
                  "index": 1,
                  "question": "面试官这一轮问什么（不超过 30 字）",
                  "points": ["核心论点 1"],
                  "blockers": ["卡壳点 1"],
                  "depth": "深"
                }
              ],
              "coverage": {
                "covered": ["已经答到的知识点"],
                "missing": ["岗位要求里明显没覆盖到的知识点"]
              },
              "vocabulary": {
                "good": ["回答里用得准确的术语"],
                "improve": ["表达上可以更准确的说法"]
              }
            }""";

    /** 分段抽取的 JSON 契约：先不要 overview，合并阶段再写。 */
    private static final String CHUNK_TEMPLATE = """
            {
              "questions": [
                {
                  "index": 1,
                  "question": "面试官这一轮问什么（不超过 30 字）",
                  "points": ["核心论点 1"],
                  "blockers": ["卡壳点 1"],
                  "depth": "深"
                }
              ],
              "coverage": {"covered": ["已经答到的知识点"], "missing": []},
              "vocabulary": {"good": [], "improve": []}
            }""";

    /**
     * 单个片段的字符预算。
     *
     * <p>取 6000：默认 {@code max_tokens=2048} 下，输入再长模型也容易省略中间几轮；
     * 6000 字大约对应 10~15 轮问答，分段后每段都能被完整读完。片段按<b>对话顺序</b>切，
     * 不丢开头也不丢结尾（与评估线「丢最早」的取舍不同：总结要覆盖全过程）。
     */
    public static final int MAX_CHUNK_CHARS = 6000;

    private SummaryPrompter() {
    }

    /** 短对话：一次生成完整摘要。 */
    public static List<LlmMessage> buildFinal(String jobCode, List<InterviewMessage> messages) {
        return List.of(LlmMessage.system(finalSystemPrompt()),
                LlmMessage.user(finalUserPrompt(jobCode, messages)));
    }

    /**
     * 长对话的一个片段。
     *
     * @param part  片段序号（1-based）
     * @param total 片段总数
     */
    public static List<LlmMessage> buildChunk(String jobCode, List<InterviewMessage> chunk,
                                              int part, int total) {
        return List.of(LlmMessage.system(chunkSystemPrompt()),
                LlmMessage.user(chunkUserPrompt(jobCode, chunk, part, total)));
    }

    /** 合并：输入各片段的 JSON 原文，输出完整摘要。 */
    public static List<LlmMessage> buildMerge(String jobCode, List<String> chunkResults) {
        StringBuilder user = new StringBuilder();
        String job = jobCode == null || jobCode.isBlank() ? "未指定" : jobCode.trim();
        user.append("目标岗位：").append(job).append('\n').append('\n');
        user.append("下面是同一场面试按时间顺序分段整理的结果（每段都是 JSON）：").append('\n');
        for (int i = 0; i < chunkResults.size(); i++) {
            user.append("第 ").append(i + 1).append(" 段：").append('\n')
                    .append(chunkResults.get(i) == null ? "" : chunkResults.get(i).strip()).append('\n');
        }
        user.append('\n').append("请把这些片段合并成一份完整摘要：逐轮记录按顺序重新编号（从 1 开始连续），")
                .append("同一种知识点只保留一次，不要再分「段」。")
                .append('\n').append("请按 system 里给出的 JSON 模板输出。");
        return List.of(LlmMessage.system(finalSystemPrompt()), LlmMessage.user(user.toString()));
    }

    /**
     * 把对话按字符预算切段。
     *
     * <p>按整条台词切，不在一句话中间截断——半句台词会让模型误判「卡壳」。
     * 一条台词本身就超预算时单独成段（宁可超一点也不能丢内容）。
     * 空列表返回空列表，由调用方判定「没有可总结的内容」。
     */
    public static List<List<InterviewMessage>> split(List<InterviewMessage> messages) {
        List<List<InterviewMessage>> chunks = new ArrayList<>();
        List<InterviewMessage> current = new ArrayList<>();
        int used = 0;
        for (InterviewMessage message : ordered(messages)) {
            String line = lineOf(message);
            if (!current.isEmpty() && used + line.length() > MAX_CHUNK_CHARS) {
                chunks.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(message);
            used += line.length() + 1;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    /** 全体对话的字数（按 {@link #lineOf} 的口径），供服务层判断是否需要分块。 */
    public static int totalChars(List<InterviewMessage> messages) {
        int total = 0;
        for (InterviewMessage message : ordered(messages)) {
            total += lineOf(message).length() + 1;
        }
        return total;
    }

    private static String finalSystemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append(ROLE).append('\n').append('\n');
        builder.append(OUTPUT_RULE).append('\n').append('\n');
        builder.append(NEUTRAL_RULE).append('\n').append('\n');
        builder.append(QUESTION_RULE).append('\n').append('\n');
        builder.append("请严格按下面的 JSON 模板输出：").append('\n');
        builder.append(FINAL_TEMPLATE);
        return builder.toString();
    }

    private static String chunkSystemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append(ROLE).append('\n').append('\n');
        builder.append(OUTPUT_RULE).append('\n').append('\n');
        builder.append("现在只给你整场面试的一个片段（不是全部对话），你只需整理这个片段里发生的事实。")
                .append('\n').append('\n');
        builder.append(NEUTRAL_RULE).append('\n').append('\n');
        builder.append(QUESTION_RULE).append('\n').append('\n');
        builder.append("片段内题号按本片段出现顺序从 1 编号即可（最终合并时会重新编号）。").append('\n').append('\n');
        builder.append("请严格按下面的 JSON 模板输出：").append('\n');
        builder.append(CHUNK_TEMPLATE);
        return builder.toString();
    }

    private static String finalUserPrompt(String jobCode, List<InterviewMessage> messages) {
        String job = jobCode == null || jobCode.isBlank() ? "未指定" : jobCode.trim();
        StringBuilder builder = new StringBuilder();
        builder.append("目标岗位：").append(job).append('\n').append('\n');
        builder.append("以下是本场面试的完整对话记录（按时间顺序）：").append('\n');
        appendLines(builder, messages);
        builder.append('\n').append("请按 system 里给出的 JSON 模板输出摘要。");
        return builder.toString();
    }

    private static String chunkUserPrompt(String jobCode, List<InterviewMessage> chunk,
                                          int part, int total) {
        String job = jobCode == null || jobCode.isBlank() ? "未指定" : jobCode.trim();
        StringBuilder builder = new StringBuilder();
        builder.append("目标岗位：").append(job).append('\n');
        builder.append("这是整场面试的第 ").append(part).append(" / ").append(total).append(" 段。").append('\n').append('\n');
        builder.append("本段对话记录（按时间顺序）：").append('\n');
        appendLines(builder, chunk);
        builder.append('\n').append("请按 system 里给出的 JSON 模板输出本段的整理结果。");
        return builder.toString();
    }

    private static void appendLines(StringBuilder builder, List<InterviewMessage> messages) {
        for (InterviewMessage message : ordered(messages)) {
            builder.append(lineOf(message)).append('\n');
        }
    }

    private static List<InterviewMessage> ordered(List<InterviewMessage> messages) {
        List<InterviewMessage> ordered = new ArrayList<>();
        if (messages != null) {
            for (InterviewMessage message : messages) {
                // system 行是给模型的内部指令，不是对话内容，混进来既占预算又会让模型困惑
                if (message.getRole() != null && !InterviewMessage.ROLE_SYSTEM.equals(message.getRole())) {
                    ordered.add(message);
                }
            }
        }
        ordered.sort(Comparator.comparing(message -> message.getSeq() == null ? 0 : message.getSeq()));
        return ordered;
    }

    private static String lineOf(InterviewMessage message) {
        String content = message.getContent() == null ? "" : message.getContent().strip();
        return speakerOf(message.getRole()) + content;
    }

    /** role → 展示名；user 是求职者、assistant 是面试官，其余（含 null）按旁白处理。 */
    private static String speakerOf(String role) {
        if (InterviewMessage.ROLE_USER.equals(role)) {
            return "求职者：";
        }
        if (InterviewMessage.ROLE_ASSISTANT.equals(role)) {
            return "面试官：";
        }
        return "旁白：";
    }
}

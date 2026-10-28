package cn.edu.zjut.interview.evaluation;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 评估 prompt 的组装（纯静态、可单测，<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>与 {@code PromptAssembler} 刻意分开：那边组的是「面试进行中」的对话 prompt，
 * 这边组的是「面试结束后」的打分 prompt——两者的角色设定、输出格式、长度预算都不同，
 * 塞进一个类会让两边互相牵制。
 */
public final class EvaluationPrompter {

    /** 角色设定。 */
    private static final String ROLE = "你是资深技术面试官与人才评估专家，"
            + "负责根据一场模拟面试的完整对话记录，对求职者的回答做结构化评估。";

    /**
     * 输出格式的硬约束。这句必须原样出现在 system 里——模型最容易违反的就是「顺手加个围栏和开场白」，
     * 护栏得先立住（{@link EvaluationParser} 里虽然也做了围栏剥离，但那是兜底，不是许可证）。
     */
    private static final String OUTPUT_RULE =
            "只输出 JSON，不要 Markdown 围栏、不要任何解释文字。";

    /** 评分区间锚点：不给锚点的话模型会把 75 当满分用，四个人评出来分布完全不同。 */
    private static final String SCORE_ANCHOR =
            "评分区间锚点：>=85 优秀 / 70-84 合格 / 50-69 待提升 / <50 明显不足。";

    /** 给模型的 JSON 契约，与 {@link EvaluationParser} 的解析口径一一对应。 */
    private static final String JSON_TEMPLATE = """
            {
              "summary": "一句话总评（不超过 60 字）",
              "strengths": ["亮点 1", "亮点 2"],
              "weaknesses": ["不足 1", "不足 2"],
              "suggestions": ["改进建议 1", "改进建议 2"],
              "dimensions": {
                "technical": {"score": 0, "comment": "..."},
                "depth":     {"score": 0, "comment": "..."},
                "logic":     {"score": 0, "comment": "..."},
                "fit":       {"score": 0, "comment": "..."}
              }
            }""";

    /**
     * user 消息的长度预算（字符数）。
     *
     * <p>取舍：超预算时<b>从最早的消息开始丢</b>——面试结尾的回答通常是压轴的深度题，
     * 信息密度比开头的「请做个自我介绍」高得多，丢开头对评分的影响最小。
     * 反过来「保留开头、丢结尾」会让模型只看到暖场阶段，评出来一片虚高。
     */
    private static final int MAX_USER_CHARS = 8000;

    private EvaluationPrompter() {
    }

    /**
     * 组出评估用的两条消息：system（角色 + 口径 + 模板）+ user（岗位 + 对话记录）。
     *
     * @param jobCode  目标岗位 code（可能为 null，此时 user 里写「未指定」）
     * @param messages 本场台词，方法内部按 seq 升序排并跳过 system 行
     */
    public static List<LlmMessage> build(String jobCode, List<InterviewMessage> messages) {
        return List.of(LlmMessage.system(systemPrompt()), LlmMessage.user(userPrompt(jobCode, messages)));
    }

    private static String systemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append(ROLE).append('\n').append('\n');
        builder.append(OUTPUT_RULE).append('\n').append('\n');
        builder.append("四个维度的判定口径：").append('\n');
        for (EvaluationDimension dimension : EvaluationDimension.all()) {
            builder.append("- ").append(dimension.code()).append('(').append(dimension.label()).append(")：")
                    .append(criterionOf(dimension)).append("（权重 ").append(dimension.weight()).append("）\n");
        }
        builder.append('\n').append(SCORE_ANCHOR).append('\n').append('\n');
        builder.append("请严格按下面的 JSON 模板输出，四个维度缺一不可，score 为 0-100 的整数：").append('\n');
        builder.append(JSON_TEMPLATE);
        return builder.toString();
    }

    /** 每个维度一句话口径，用的是枚举里的中文名（改这里等于改判定标准，要同步改单测）。 */
    private static String criterionOf(EvaluationDimension dimension) {
        return switch (dimension) {
            case TECHNICAL -> "回答里的技术事实、术语、原理是否准确，有没有说错或张冠李戴";
            case DEPTH -> "是否讲清了底层原理与取舍，而不是停在背诵定义或用法的层面";
            case LOGIC -> "论述是否自洽、层次是否分明，结论有没有被给出的论据支撑";
            case FIT -> "回答的内容与目标岗位的技能要求、工作场景的贴合程度";
        };
    }

    private static String userPrompt(String jobCode, List<InterviewMessage> messages) {
        String job = jobCode == null || jobCode.isBlank() ? "未指定" : jobCode.trim();
        String footer = "\n请按 system 里给出的四个维度与 JSON 模板输出评估结果。";
        StringBuilder header = new StringBuilder();
        header.append("目标岗位：").append(job).append('\n').append('\n');
        header.append("以下是本场面试的对话记录（按时间顺序）：").append('\n');

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

        List<String> lines = new ArrayList<>(ordered.size());
        for (InterviewMessage message : ordered) {
            String content = message.getContent() == null ? "" : message.getContent().strip();
            lines.add(speakerOf(message.getRole()) + content);
        }
        // 预算按整条 user 消息（岗位头 + 对话 + 收尾要求）算：
        // 只卡对话部分的话，加上头尾仍会超出发给模型的长度
        int used = header.length() + footer.length();
        for (String line : lines) {
            used += line.length() + 1;
        }
        int index = 0;
        while (used > MAX_USER_CHARS && index < lines.size() - 1) {
            used -= lines.get(index).length() + 1;
            index++;
        }

        StringBuilder builder = new StringBuilder(header);
        for (int i = index; i < lines.size(); i++) {
            builder.append(lines.get(i)).append('\n');
        }
        builder.append(footer);
        return builder.toString();
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

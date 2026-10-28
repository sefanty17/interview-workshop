package cn.edu.zjut.interview.evaluation;

import java.util.List;

/**
 * 内容分析的四个维度（<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>题目原文就这四个维度、权重也写死在题目里（技术正确性 0.30 / 知识深度 0.25 /
 * 逻辑严谨性 0.25 / 岗位匹配度 0.20），所以做成枚举而不是可配置项——
 * 配置化在这个规模下是过度设计，而且会引出一个永远没人用的「自定义维度」界面。
 *
 * <p>权重和恒为 1.0（{@code 0.30 + 0.25 + 0.25 + 0.20}）：改这里的权重要同步改
 * {@link EvaluationService} 里「总分 = 各维度分 × 权重之和」那几行注释与
 * {@link EvaluationPrompter} 给模型的 JSON 模板说明，否则模型按旧权重理解评分含义。
 */
public enum EvaluationDimension {

    /** 技术正确性：说的技术内容对不对。 */
    TECHNICAL("technical", "技术正确性", 0.30),
    /** 知识深度：有没有超出背诵层面的理解。 */
    DEPTH("depth", "知识深度", 0.25),
    /** 逻辑严谨性：表达是否自洽、论据是否支撑结论。 */
    LOGIC("logic", "逻辑严谨性", 0.25),
    /** 岗位匹配度：内容与目标岗位的贴合程度。 */
    FIT("fit", "岗位匹配度", 0.20);

    private final String code;
    private final String label;
    private final double weight;

    EvaluationDimension(String code, String label, double weight) {
        this.code = code;
        this.label = label;
        this.weight = weight;
    }

    /** 落在 {@code interview_evaluation_dimension.dim_code} 里的值，也是给模型的 JSON 键名。 */
    public String code() {
        return code;
    }

    /** 中文展示名，同时进 prompt（模型按中文口径打分）。 */
    public String label() {
        return label;
    }

    /** 该维度在总分里的权重，四个加起来为 1.0。 */
    public double weight() {
        return weight;
    }

    /**
     * 按 code 反查枚举；认不出直接抛 {@link EvaluationException} 而不是返回 {@code null}。
     *
     * <p>返回 null 的代价是调用方忘了判空就 NPE，而 NPE 在评估链路里会被当成「系统错误 500」，
     * 把「模型给了一个未知维度」这种数据问题伪装成服务故障。
     */
    public static EvaluationDimension of(String code) {
        for (EvaluationDimension dimension : values()) {
            if (dimension.code.equals(code)) {
                return dimension;
            }
        }
        throw new EvaluationException(EvaluationException.BAD_DIMENSION, "未知评估维度：" + code);
    }

    /** 四个维度的固定顺序（= prompt 模板与落库顺序）。 */
    public static List<EvaluationDimension> all() {
        return List.of(values());
    }
}

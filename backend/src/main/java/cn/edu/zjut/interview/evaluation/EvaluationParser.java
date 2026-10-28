package cn.edu.zjut.interview.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把模型的输出解析成结构化评估草稿（纯静态、可单测，<b>本项目扩展（课设指标 ③）</b>）。
 *
 * <p>解析策略是「能救就救、救不了就报错」：
 * 围栏、前后废话、越界的分数都救（这些是模型的表达习惯，不代表它没评估）；
 * 缺维度、分数不是数字、根本不是 JSON 就报错（这些救不出来，硬凑就是假数据）。
 *
 * <p>用 {@link ObjectMapper} 而不是手拼字符串：模型偶尔会在 comment 里写引号和换行，
 * 正则/截取在这种输入上必错。
 */
public final class EvaluationParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 报错时带上原文的字数上限，防止把一整段胡言乱语塞进日志和响应体。 */
    private static final int ERROR_SNIPPET = 200;

    private EvaluationParser() {
    }

    /** 单个维度的分与评语。 */
    public record DimScore(int score, String comment) {
    }

    /**
     * 解析结果：落库前的中间态，还带着维度 map（{@code EvaluationService} 按
     * {@link EvaluationDimension#all()} 的顺序展开成四行）。
     *
     * @param overall 加权总分 {@code round(Σ 各维度分 × 权重)}
     */
    public record EvaluationDraft(String summary,
                                  List<String> strengths,
                                  List<String> weaknesses,
                                  List<String> suggestions,
                                  Map<String, DimScore> dimensions,
                                  int overall) {
    }

    /**
     * 解析模型的原始输出。
     *
     * @throws EvaluationException {@code EVAL_BAD_JSON} / {@code EVAL_MISSING_DIMENSION}
     */
    public static EvaluationDraft parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        String unfenced = stripFences(text);
        int start = unfenced.indexOf('{');
        int end = unfenced.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new EvaluationException(EvaluationException.BAD_JSON,
                    "模型输出里找不到 JSON 对象：" + snippet(text));
        }
        String json = unfenced.substring(start, end + 1);

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new EvaluationException(EvaluationException.BAD_JSON,
                    "模型输出的不是合法 JSON：" + snippet(text), e);
        }

        String summary = root.path("summary").isTextual() ? root.path("summary").asText() : "";
        // 这三样是锦上添花：缺了不该让整份评估作废，所以「不是数组」一律降级成空数组而不是报错
        List<String> strengths = stringArray(root.path("strengths"));
        List<String> weaknesses = stringArray(root.path("weaknesses"));
        List<String> suggestions = stringArray(root.path("suggestions"));

        JsonNode dimensions = root.path("dimensions");
        Map<String, DimScore> scores = new LinkedHashMap<>();
        double weighted = 0.0;
        for (EvaluationDimension dimension : EvaluationDimension.all()) {
            JsonNode node = dimensions.path(dimension.code());
            if (node.isMissingNode() || !node.isObject()) {
                throw new EvaluationException(EvaluationException.MISSING_DIMENSION,
                        "模型输出缺少维度：" + dimension.code() + "（" + dimension.label() + "）");
            }
            JsonNode scoreNode = node.path("score");
            if (!scoreNode.isNumber()) {
                throw new EvaluationException(EvaluationException.MISSING_DIMENSION,
                        "维度 " + dimension.code() + "（" + dimension.label() + "）的 score 不是数字："
                                + node.toString());
            }
            // 越界 clamp 而不是报错：模型偶尔给 105 或 -3，这时「整份评估作废、让用户重跑十几秒」
            // 比「当 100 / 0 处理」体验差得多。clamp 只影响这一个维度，其余维度照常。
            int score = clamp((int) Math.round(scoreNode.asDouble()));
            String comment = node.path("comment").isTextual() ? node.path("comment").asText() : "";
            scores.put(dimension.code(), new DimScore(score, comment));
            weighted += score * dimension.weight();
        }
        return new EvaluationDraft(summary, strengths, weaknesses, suggestions, scores,
                (int) Math.round(weighted));
    }

    /** 非数组（含字符串、对象、缺失）都降级成空数组。 */
    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item.isTextual()) {
                values.add(item.asText());
            } else if (!item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    /**
     * 剥掉 Markdown 围栏。只处理首尾：正文里出现反引号是正常内容，不能动。
     *
     * <p>模型经常只写开头的 ```json 而漏掉结尾的 ```，所以开头、结尾分开判。
     */
    private static String stripFences(String text) {
        String result = text;
        if (result.startsWith("```")) {
            int newline = result.indexOf('\n');
            result = newline < 0 ? "" : result.substring(newline + 1);
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.length() - 3);
        }
        return result.strip();
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private static String snippet(String text) {
        return text.length() <= ERROR_SNIPPET ? text : text.substring(0, ERROR_SNIPPET) + "…";
    }
}

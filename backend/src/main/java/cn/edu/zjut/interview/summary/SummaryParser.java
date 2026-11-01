package cn.edu.zjut.interview.summary;

import cn.edu.zjut.interview.dto.SummaryResult;
import cn.edu.zjut.interview.dto.SummaryResult.Coverage;
import cn.edu.zjut.interview.dto.SummaryResult.QuestionNote;
import cn.edu.zjut.interview.dto.SummaryResult.Vocabulary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把总结 agent 的输出解析成结构化摘要（纯静态、可单测，<b>本项目扩展（issue #4）</b>）。
 *
 * <p>策略与 {@code EvaluationParser} 一致但更宽松：总结里的每一项都是「锦上添花」，
 * 缺 points / coverage / vocabulary 时降级成空列表，而不是整份作废——
 * 让用户为了一个可选字段重跑十几秒，比少显示一栏更糟。
 * 只有「完全找不到 JSON 对象 / 不是合法 JSON」才算失败。
 */
public final class SummaryParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 报错时带上原文的字数上限，防止把一整段胡言乱语塞进日志和响应体。 */
    private static final int ERROR_SNIPPET = 200;

    /** 深度标记的合法值；模型偶尔会写「较深」这类变体，统一归一。 */
    private static final List<String> DEPTHS = List.of("深", "中", "浅");

    private SummaryParser() {
    }

    /**
     * 解析结果：落库前的中间态。
     *
     * @param overview 客观概述，模型没给时为空串
     */
    public record SummaryDraft(String overview,
                               List<QuestionNote> questions,
                               Coverage coverage,
                               Vocabulary vocabulary) {
    }

    /**
     * 解析模型的原始输出。
     *
     * @throws SummaryException {@code SUMMARY_BAD_JSON}
     */
    public static SummaryDraft parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        String unfenced = stripFences(text);
        int start = unfenced.indexOf('{');
        int end = unfenced.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new SummaryException(SummaryException.BAD_JSON,
                    "模型输出里找不到 JSON 对象：" + snippet(text));
        }
        String json = unfenced.substring(start, end + 1);

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new SummaryException(SummaryException.BAD_JSON,
                    "模型输出的不是合法 JSON：" + snippet(text), e);
        }

        String overview = root.path("overview").isTextual() ? root.path("overview").asText() : "";
        return new SummaryDraft(overview,
                questions(root.path("questions")),
                coverage(root.path("coverage")),
                vocabulary(root.path("vocabulary")));
    }

    /** 逐轮记录。非数组 → 空列表；题号缺失时按出现顺序补号。 */
    private static List<QuestionNote> questions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<QuestionNote> notes = new ArrayList<>(node.size());
        int fallbackIndex = 1;
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            int index = item.path("index").isNumber() ? item.path("index").asInt() : fallbackIndex;
            notes.add(new QuestionNote(
                    index,
                    text(item, "question"),
                    stringArray(item.path("points")),
                    stringArray(item.path("blockers")),
                    depth(item.path("depth"))));
            fallbackIndex++;
        }
        return notes;
    }

    /** 知识点覆盖。模型写成数组或缺失都降级成两个空列表，报告页据此整段隐藏。 */
    private static Coverage coverage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Coverage(List.of(), List.of());
        }
        return new Coverage(stringArray(node.path("covered")), stringArray(node.path("missing")));
    }

    /** 用词记录，口径同 {@link #coverage}。 */
    private static Vocabulary vocabulary(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Vocabulary(List.of(), List.of());
        }
        return new Vocabulary(stringArray(node.path("good")), stringArray(node.path("improve")));
    }

    private static String depth(JsonNode node) {
        String value = node.isTextual() ? node.asText().strip() : "";
        for (String allowed : DEPTHS) {
            if (value.contains(allowed)) {
                return allowed;
            }
        }
        return "中";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    /** 非数组（含字符串、对象、缺失）都降级成空数组。 */
    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    /** 剥掉 Markdown 围栏，处理方式与 {@code EvaluationParser} 一致（首尾分开判）。 */
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

    private static String snippet(String text) {
        return text.length() <= ERROR_SNIPPET ? text : text.substring(0, ERROR_SNIPPET) + "…";
    }
}

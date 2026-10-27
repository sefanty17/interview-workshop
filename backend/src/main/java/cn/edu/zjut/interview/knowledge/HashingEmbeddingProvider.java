package cn.edu.zjut.interview.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 JDK 的「假向量」实现：字符 n-gram 哈希 + L2 归一化。
 *
 * <p>不是语义模型，相似度只反映<b>字面重合</b>（共享「内存泄漏」这类词才得分高）。
 * 之所以默认用它：零依赖、断网可演示、单测确定性好——整条「切片 → 向量 → 检索」
 * 链路能先跑通，真模型接进来只是换个 {@link EmbeddingProvider}。没有外部调用，所以恒可用。
 *
 * <p>算法（勿随意改动，单测按这个行为断言）：
 * <ol>
 *   <li>小写 + trim；</li>
 *   <li>提特征：拉丁词取 {@code [a-z0-9_]+}；连续汉字取相邻两字的 bigram，单字取其本身；</li>
 *   <li>每个特征的 {@code hashCode()} 取模 512 落桶并 +1；</li>
 *   <li>L2 归一化（全零向量原样返回，避免除零）。</li>
 * </ol>
 */
public class HashingEmbeddingProvider implements EmbeddingProvider {

    /** 固定维度：单测与 DDL 断言都依赖这个常量，换值等于换模型。 */
    private static final int DIMENSION = 512;

    /** 模型名写进 knowledge_vector.model，检索时按它过滤。 */
    private static final String MODEL_NAME = "hashing-512";

    /** 拉丁词特征：小写之后只认字母、数字、下划线。 */
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9_]+");

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        for (String feature : features(text)) {
            int index = Math.floorMod(feature.hashCode(), DIMENSION);
            vector[index] += 1.0f;
        }
        return normalize(vector);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }

    /**
     * 提特征：拉丁词 + 汉字 bigram。
     *
     * <p>中文按「相邻两字」而不是按词，是因为这里拿不到分词器（加依赖被明令禁止），
     * bigram 在中文上的召回已经够用。
     */
    private static List<String> features(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        Matcher matcher = LATIN_TOKEN.matcher(normalized);
        while (matcher.find()) {
            out.add(matcher.group());
        }

        int length = normalized.length();
        int i = 0;
        while (i < length) {
            int codePoint = normalized.codePointAt(i);
            if (!isHan(codePoint)) {
                i += Character.charCount(codePoint);
                continue;
            }
            // 收集一段连续的汉字，段内再切 bigram
            List<Integer> run = new ArrayList<>();
            int j = i;
            while (j < length) {
                int next = normalized.codePointAt(j);
                if (!isHan(next)) {
                    break;
                }
                run.add(next);
                j += Character.charCount(next);
            }
            if (run.size() == 1) {
                out.add(new String(Character.toChars(run.get(0))));
            } else {
                for (int k = 0; k + 1 < run.size(); k++) {
                    out.add(new String(Character.toChars(run.get(k)))
                            + new String(Character.toChars(run.get(k + 1))));
                }
            }
            i = j;
        }
        return out;
    }

    /** 汉字判定按 Unicode script，而不是码段区间：这样扩展区汉字也能被识别。 */
    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** L2 归一化；零向量直接返回，避免 0 除以 0 得到 NaN 污染余弦计算。 */
    private static float[] normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        if (sum == 0.0) {
            return vector;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}

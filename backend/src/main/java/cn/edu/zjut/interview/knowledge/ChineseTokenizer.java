package cn.edu.zjut.interview.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 检索用的极简分词器：拉丁/数字词整段切出，连续汉字切成相邻两字的 bigram。
 *
 * <p>不引第三方分词库（项目明令零新增依赖），中文 bigram 在本项目这个语料量级上
 * 召回够用，而且与 {@link HashingEmbeddingProvider} 的特征口径一致——
 * 两路召回才互补得起来（BM25 看词频与稀有度，向量看 bigram 重合的整体形状）。
 *
 * <p>只做切分，不持有任何状态，所以全是静态方法、可重复调用。
 */
public final class ChineseTokenizer {

    /** 工具类，不给实例化。 */
    private ChineseTokenizer() {
    }

    /**
     * 切片：返回词项列表，全部小写。
     *
     * <p><b>不去重、不合并</b>：BM25 要靠词频（tf）打分，去重会把这些信息抹掉；
     * 同一个词既出现在拉丁段又出现在中文段也是正常的。
     */
    public static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        int length = normalized.length();
        int i = 0;
        while (i < length) {
            char current = normalized.charAt(i);
            if (isLatinChar(current)) {
                int end = i;
                while (end < length && isLatinChar(normalized.charAt(end))) {
                    end++;
                }
                terms.add(normalized.substring(i, end));
                i = end;
            } else if (isHanChar(current)) {
                int end = i;
                while (end < length && isHanChar(normalized.charAt(end))) {
                    end++;
                }
                int runLength = end - i;
                if (runLength == 1) {
                    // 段内只有一个字，没有相邻字可组，只能原样输出
                    terms.add(normalized.substring(i, end));
                } else {
                    for (int k = i; k + 1 < end; k++) {
                        terms.add(normalized.substring(k, k + 2));
                    }
                }
                i = end;
            } else {
                // 标点、空白、全角符号一律当段落分隔符
                i++;
            }
        }
        return terms;
    }

    /**
     * 拉丁/数字段：字母数字且不在 CJK 码段内，另加 {@code . + # _ -} 这几个技术词里常见的符号。
     *
     * <p>带上符号是为了 {@code spring-boot}、{@code c++}、{@code g1}、{@code full-gc} 这类词不被切断。
     */
    private static boolean isLatinChar(char c) {
        if (c == '.' || c == '+' || c == '#' || c == '_' || c == '-') {
            return true;
        }
        return Character.isLetterOrDigit(c) && c < 0x2E80;
    }

    /** 汉字按码段判定（规格指定 4E00-9FFF），与 {@link HashingEmbeddingProvider} 的 script 判定不冲突。 */
    private static boolean isHanChar(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}

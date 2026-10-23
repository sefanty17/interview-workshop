package cn.edu.zjut.interview.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式分句器，对应 {@code message_system/producer.rs:89}。
 *
 * <p><b>按 {@code 【} 标签边界切，不按句号切</b>：一段正文要等到下一个 {@code 【} 出现才算完整，
 * 所以切分结果与网络 chunk 边界无关（{@link #feed(String)} 可以按任意粒度喂，结果完全一致）。
 *
 * <p>越界保护来自 {@code producer.rs:110}：命中回合结束标记后，后续内容一律丢弃并置
 * {@link #isTurnEnded()}。标记只有两条（{@code processor.rs:428/434}）：
 * <ol>
 *   <li>整行 {@code user} 标签（可带冒号/空白，行首可有空白）——模型替玩家发言的典型形状；</li>
 *   <li>连续两个空行（三个换行）——记忆构建器给模型照抄的「本轮到此为止」形状。</li>
 * </ol>
 * 两条都要求「标记之前已经有正文」，否则回复开头的空行会把整段截成空。
 *
 * <p><b>两个可调项</b>（都来自后端设置，规格 B4-BE §三；传 0 时行为与不带设置完全一致）：
 * <ul>
 *   <li>{@code mergeThreshold} ← {@code chat.merge_threshold}：短于此字数的句子并入下一句，
 *       0 = 不合并（本项目扩展，原版由前端做合并）；</li>
 *   <li>{@code maxSentences} ← {@code chat.reply_count}：本轮最多投递几句，多出的直接丢弃，
 *       0 = 不限制（本项目扩展）。</li>
 * </ul>
 */
public final class SentenceSplitter {

    /** 越界标记一：整行 {@code user}（{@code processor.rs:429}）。 */
    private static final Pattern USER_LABEL =
            Pattern.compile("(?m)^[ \\t]*user[ \\t]*[:：]?[ \\t]*\\r?\\n");
    /** 越界标记二：连续两个空行（{@code processor.rs:436}）。 */
    private static final Pattern BLANK_GAP = Pattern.compile("\\n[ \\t]*\\r?\\n[ \\t]*\\r?\\n");

    private static final char TAG_OPEN = '【';
    private static final char TAG_CLOSE = '】';

    /** 未投递的尾巴（可能含未闭合的标签）。 */
    private final StringBuilder buffer = new StringBuilder();
    /** 当前字符是否落在 {@code 【…】} 标签内部。 */
    private boolean inTag;
    /** buffer 里是否已经出现过闭合的 {@code 】}（有才允许在下一个 {@code 【} 处切句）。 */
    private boolean tagClosed;
    private int sentenceIndex;
    private boolean turnEnded;

    /** 合并阈值（{@code chat.merge_threshold}），0 = 不合并。 */
    private final int mergeThreshold;
    /** 合并中的短句，等下一条到达时拼上去。 */
    private final StringBuilder carry = new StringBuilder();
    /** 本轮最多投递几句（{@code chat.reply_count}），0 = 不限制。 */
    private final int maxSentences;

    /** 不带设置的分句器：不合并、不限句数（等价于原版行为，测试与旧调用点用这个）。 */
    public SentenceSplitter() {
        this(0, 0);
    }

    /**
     * @param mergeThreshold 合并阈值（{@code chat.merge_threshold}），{@code <=0} 表示不合并
     * @param maxSentences   本轮最多投递几句（{@code chat.reply_count}），{@code <=0} 表示不限制
     */
    public SentenceSplitter(int mergeThreshold, int maxSentences) {
        this.mergeThreshold = Math.max(0, mergeThreshold);
        this.maxSentences = Math.max(0, maxSentences);
    }

    /**
     * 增量喂入一个 chunk。
     *
     * @param chunk 模型流式片段，可为 {@code null}
     * @return 本 chunk 内凑齐的句子（可能 0 条），{@code isFinal} 恒为 false
     */
    public List<Sentence> feed(String chunk) {
        List<Sentence> emitted = new ArrayList<>();
        if (chunk == null || chunk.isEmpty()) {
            return emitted;
        }
        for (int i = 0; i < chunk.length(); i++) {
            if (turnEnded) {
                // 越界之后的内容一律丢弃（Rust: if turn_ended { continue })
                break;
            }
            char c = chunk.charAt(i);
            if (c == TAG_OPEN && !inTag) {
                if (tagClosed) {
                    // 上一段已经「标签闭合 + 有正文」，这个 【 就是它的右边界
                    deliver(emitted, takeBuffer(), false);
                    tagClosed = false;
                }
                inTag = true;
            } else if (c == TAG_CLOSE) {
                inTag = false;
                tagClosed = true;
            }
            buffer.append(c);
            if (truncateAtTurnEnd()) {
                turnEnded = true;
                break;
            }
        }
        return emitted;
    }

    /**
     * 收尾：剩余缓冲作为最后一句投递（{@code isFinal=true}）。重复调用返回空列表。
     *
     * <p>合并中的短句也在这一步强制吐出，不会被吞掉。
     */
    public List<Sentence> finish() {
        List<Sentence> emitted = new ArrayList<>(1);
        if (!buffer.isEmpty() || carry.length() > 0) {
            deliver(emitted, takeBuffer(), true);
        }
        return emitted;
    }

    /** 是否已命中回合结束标记（此后 {@link #feed(String)} 不再产出任何句子）。 */
    public boolean isTurnEnded() {
        return turnEnded;
    }

    /** 下一个句子的序号，也就是已投递的句子数。 */
    public int nextIndex() {
        return sentenceIndex;
    }

    private String takeBuffer() {
        String text = buffer.toString();
        buffer.setLength(0);
        return text;
    }

    /**
     * 投递一句（合并 + 限句都在这里）。
     *
     * <p>不开启（阈值/上限都是 0）时与原来逐字等价：一句就是一句、序号照增。
     *
     * @param out     收集本次投递结果的列表
     * @param text    刚切出来的一句原文
     * @param isFinal 是否是收尾那一句
     */
    private void deliver(List<Sentence> out, String text, boolean isFinal) {
        if (maxSentences > 0 && sentenceIndex >= maxSentences) {
            // chat.reply_count 上限：多出来的台词直接丢（prompt 侧也提示了同样的句数）
            return;
        }
        String merged = text;
        if (mergeThreshold > 0) {
            merged = carry + text;
            carry.setLength(0);
            // 太短且不是最后一句 → 攒着，等下一句拼上再投递（0 表示不合并，这条路走不到）
            if (!isFinal && merged.strip().length() < mergeThreshold) {
                carry.append(merged);
                return;
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        out.add(new Sentence(merged, sentenceIndex++, isFinal));
    }

    /**
     * 命中越界标记就截断缓冲（标记本身也丢掉）。
     *
     * @return 是否命中
     */
    private boolean truncateAtTurnEnd() {
        int pos = findTurnEnd(buffer);
        if (pos < 0) {
            return false;
        }
        buffer.setLength(pos);
        return true;
    }

    /** 对应 {@code processor.rs:445 find_turn_end}：取两条标记里更靠前的那个。 */
    private static int findTurnEnd(CharSequence text) {
        int userLabel = findAfterContent(text, USER_LABEL);
        int blankGap = findAfterContent(text, BLANK_GAP);
        if (userLabel < 0) {
            return blankGap;
        }
        if (blankGap < 0) {
            return userLabel;
        }
        return Math.min(userLabel, blankGap);
    }

    /** 只在「标记之前已经有正文」时才认这条标记（Rust 的 {@code non_empty_prefix}）。 */
    private static int findAfterContent(CharSequence text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (!text.subSequence(0, matcher.start()).toString().strip().isEmpty()) {
                return matcher.start();
            }
        }
        return -1;
    }

    /**
     * 投递出去的一句。
     *
     * @param text    原文（含 {@code 【情绪】} 标签，未做任何清洗）
     * @param index   从 0 开始、严格递增；被丢弃的越界内容不占号
     * @param isFinal 是否为本轮最后一句
     */
    public record Sentence(String text, int index, boolean isFinal) {
    }
}

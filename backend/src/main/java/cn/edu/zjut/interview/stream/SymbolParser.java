package cn.edu.zjut.interview.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 情绪段解析 + 用户消息预处理，对应 {@code message_system/processor.rs:120}。
 *
 * <p>解析侧只做「结构化」，不做翻译、不做 TTS、不做 ONNX 情绪分类：
 * {@code predicted} 由 {@link EmotionDictionary} 的别名表折叠，查不到就原样保留。
 *
 * <p>与 Rust 的差异（都是为了本项目没有的能力）：
 * <ul>
 *   <li>{@code enable_translate=false} 时 Rust 会把 {@code japanese_text} 覆盖成 cleaned_text；
 *       本项目按规格语义只用 {@code <...>} 里的日语原文，没有则为空串。</li>
 *   <li>时间感知提示（{@code time_sense_enabled}）固定关闭。</li>
 *   <li>桌面分析、语音文件、角色挂载不迁。</li>
 * </ul>
 */
public final class SymbolParser {

    /** 情绪 + 正文：规格 §4.2 第 1 条。 */
    private static final Pattern EMOTION = Pattern.compile("【([^】]*)】([^【】]*)");
    /** 日语：第 2 条。 */
    private static final Pattern JAPANESE = Pattern.compile("<([^>]*)>");
    /** 动作（全角括号）：第 3 条。 */
    private static final Pattern MOTION = Pattern.compile("（([^）]*)）");
    /** 花括号块：第 4 条，用于用户消息里的旁白。 */
    private static final Pattern BRACKET = Pattern.compile("\\{([^}]+)\\}");
    /** 临时指令段：第 5 条。 */
    private static final Pattern TEMP = Pattern.compile("(?s)\\[!Temp!\\](.*?)\\[/!Temp!\\]");
    /** 剥掉剩下的标签：第 6 条，三条正则合成一次替换。 */
    private static final Pattern STRIP_TAGS = Pattern.compile("<[^>]*>|（[^）]*）|【[^】]*】");
    /** 前处理用：整个花括号块（含内容）删掉。 */
    private static final Pattern CURLY = Pattern.compile("\\{[^{}]*\\}");

    private final EmotionDictionary dictionary;

    public SymbolParser() {
        this(EmotionDictionary.getInstance());
    }

    public SymbolParser(EmotionDictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * 解析一段 AI 回复。
     *
     * @param text 原始回复，可为 {@code null}
     * @return 按出现顺序排列的段；没有 {@code 【】} 标签时返回空列表
     */
    public List<EmotionSegment> parse(String text) {
        List<EmotionSegment> results = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return results;
        }
        String source = preprocess(text);

        Matcher matcher = EMOTION.matcher(source);
        int index = 0;
        // 上一段结束位置，用于「日语写在标签之前」时回填 japaneseText
        int previousEnd = 0;
        while (matcher.find()) {
            index++;
            String tag = matcher.group(1);
            // Rust: following_raw.replace('(', "（").replace(')', "）")
            String following = matcher.group(2).replace('(', '（').replace(')', '）');

            String motionText = firstGroup(MOTION, following).trim();
            String japaneseText = firstGroup(JAPANESE, following).trim();
            if (japaneseText.isEmpty()) {
                // `<日语>` 出现在标签之前（规格 §5 用例 <こんにちは>【平静】hi）
                japaneseText = firstGroup(JAPANESE, source.substring(previousEnd, matcher.start())).trim();
            }
            japaneseText = japaneseText.replace('~', '。');

            String cleanedText = STRIP_TAGS.matcher(following).replaceAll("").trim();

            if (cleanedText.isEmpty() && japaneseText.isEmpty() && motionText.isEmpty()) {
                previousEnd = matcher.end();
                continue;
            }

            results.add(new EmotionSegment(index, tag, cleanedText, motionText, japaneseText,
                    dictionary.predict(tag)));
            previousEnd = matcher.end();
        }
        return results;
    }

    /**
     * 用户消息预处理，对应 {@code processor.rs:264 append_user_message}。
     *
     * <p>{@code [!Temp!]…[/!Temp!]} 单独抽出来给调用方（Rust 的 {@code temp}），
     * 旁白（{@code {...}}）与临时段从正文里删掉后拼成 {@code {系统提醒: ...}} 尾巴。
     * 时间感知提示按规格固定关闭，因此不会出现 {@code 系统提醒: 2026/10/21 10:00 } 前缀。
     *
     * @param userMessage 玩家原始输入
     * @return {@code main}（清理后的正文）+ {@code temp}（无临时段时为 {@code null}）
     */
    public UserMessageOutcome appendUserMessage(String userMessage) {
        String raw = userMessage == null ? "" : userMessage;
        String processed = raw;
        String userInstruction = "";
        String tempInstruction = "";

        // 旁白：{...}
        List<String> narrations = allGroups(BRACKET, raw);
        if (!narrations.isEmpty()) {
            processed = BRACKET.matcher(processed).replaceAll("").trim();
            userInstruction = "旁白: " + String.join("; ", narrations);
        }

        // 临时指令：[!Temp!]...[/!Temp!]
        List<String> temps = allGroups(TEMP, raw);
        if (!temps.isEmpty()) {
            processed = TEMP.matcher(processed).replaceAll("").trim();
            List<String> pieces = new ArrayList<>(temps.size());
            for (String temp : temps) {
                pieces.add("$" + temp + "$");
            }
            tempInstruction = String.join("%", pieces);
        }

        List<String> systemParts = new ArrayList<>(2);
        if (!userInstruction.isEmpty()) {
            systemParts.add(userInstruction);
        }
        if (!tempInstruction.isEmpty()) {
            systemParts.add(tempInstruction);
        }
        if (!systemParts.isEmpty()) {
            processed = processed + "\n{" + String.join(" ", systemParts) + "}";
        }
        return new UserMessageOutcome(processed, tempInstruction.isEmpty() ? null : tempInstruction);
    }

    /**
     * 文本前处理，对应 {@code processor.rs:203 preprocess_text}：删花括号块、统一尖括号、
     * 去掉书名号（保留内容）、补全未闭合的 {@code <} 标签。
     */
    private static String preprocess(String text) {
        String processed = CURLY.matcher(text).replaceAll("");
        processed = processed.replace('＜', '<').replace('＞', '>');
        processed = processed.replace("《", "").replace("》", "");
        return fixUnclosedTags(processed);
    }

    /** 对应 {@code processor.rs:223 fix_unclosed_tags}：{@code <} 没等到 {@code >} 就在行尾补一个。 */
    private static String fixUnclosedTags(String text) {
        StringBuilder result = new StringBuilder(text.length() + 10);
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c != '<') {
                result.append(c);
                i++;
                continue;
            }
            i++;
            int contentStart = i;
            while (i < length && text.charAt(i) != '>' && text.charAt(i) != '\n') {
                i++;
            }
            result.append('<').append(text, contentStart, i);
            if (i < length && text.charAt(i) == '>') {
                result.append('>');
                i++;
            } else {
                result.append('>');
            }
        }
        return result.toString();
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> allGroups(Pattern pattern, String text) {
        List<String> groups = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            groups.add(matcher.group(1));
        }
        return groups;
    }

    /**
     * {@link #appendUserMessage(String)} 的结果，对应 {@code processor.rs:42 UserMessageOutcome}。
     *
     * @param main 清理后（可能带 {@code {…}} 提醒尾巴）的用户正文
     * @param temp 临时指令串（{@code $a$%$b$} 形式），没有则为 {@code null}
     */
    public record UserMessageOutcome(String main, String temp) {
    }
}

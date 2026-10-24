package cn.edu.zjut.interview.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧本包里的「用户声明的模式」（素材模式 / 角色卡模式），写在 {@code .agent/constraints.md}。
 *
 * <p>1:1 迁移自 {@code crates/ling-chat-main/src/utils/script_modes.rs}（187 行），措辞逐字照抄。
 * 解析规则的原话：取一行的 {@code 键：值}，认列表符号、加粗、反引号与表格行（模型这几样都写过）；
 * 值取到第一个标点为止；{@code 键} 去掉「模式」后缀。
 */
public final class ScriptModes {

    /** 约束卡相对剧本包根的路径（{@code CONSTRAINTS_REL_PATH}）。 */
    public static final String CONSTRAINTS_REL_PATH = ".agent/constraints.md";

    /** 素材与角色卡共用的松紧：一个枚举、两个取值，判据只有一种。 */
    public enum Mode {
        /** 只用磁盘上已有的 —— 写了不存在的名字就是错，必须当场换掉。 */
        ONLY_EXISTING("OnlyExisting"),
        /** 允许缺失（之后补）：缺失只登记、只提醒，不判错。默认。 */
        ALLOW_MISSING("AllowMissing");

        private final String debugName;

        Mode(String debugName) {
            this.debugName = debugName;
        }

        /** 与 Rust {@code #[derive(Debug)]} 的写法一致（{@code conflict_line} 里原样印出来）。 */
        public String debugName() {
            return debugName;
        }
    }

    /** 两类模式：素材 + 角色卡。 */
    public final Mode asset;
    public final Mode cast;

    public ScriptModes(Mode asset, Mode cast) {
        this.asset = asset;
        this.cast = cast;
    }

    /** 默认（两类都按最宽走）。 */
    public static ScriptModes defaults() {
        return new ScriptModes(Mode.ALLOW_MISSING, Mode.ALLOW_MISSING);
    }

    /** 读剧本包里的约束卡；读不到就按默认。 */
    public static ScriptModes read(Path scriptDir) {
        if (scriptDir == null) {
            return defaults();
        }
        try {
            return parse(Files.readString(scriptDir.resolve(CONSTRAINTS_REL_PATH), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return defaults();
        }
    }

    public static ScriptModes parse(String text) {
        String raw = text == null ? "" : text;
        return new ScriptModes(parseMode(raw, ASSET_KEYS), parseMode(raw, CAST_KEYS));
    }

    /** 交接单里的模式一行；{@code what} 是「素材」或「角色卡」。 */
    public static String describeOf(Mode mode, String what) {
        if (mode == Mode.ONLY_EXISTING) {
            return what + "模式是「只用已有」：引用磁盘上没有的就是错，当场改";
        }
        return what + "模式是「允许缺失」：缺的只提醒、不判错，之后补上即可";
    }

    /**
     * 换模式的话术：用户说一句就能改，已落盘的章节不会跟着重写。
     */
    public static String switchHint(Mode mode, String what) {
        if (mode == Mode.ONLY_EXISTING) {
            return what + "模式是「只用已有」。若其实打算之后补，"
                    + "说一句「" + what + "我之后补，先留位置」，我就改成「允许缺失」，这些就不再拦；"
                    + "已落盘的章节不用重写。\n";
        }
        return what + "模式是「允许缺失」：磁盘上还没有的只提醒、不判错，之后补上即可。"
                + "若想改成「只用已有」，说一句就行；改成后已落盘章节里缺的会变成必须修的错。\n";
    }

    /** 素材模式那一行。 */
    public String assetDescribe() {
        return describeOf(asset, "素材");
    }

    /** 角色卡模式那一行。 */
    public String castDescribe() {
        return describeOf(cast, "角色卡");
    }

    /**
     * 卡片里对同一模式给出互相矛盾的说法时返回一句提示：判定取最后一条，但要把矛盾摊开让人看见。
     */
    public static String conflictNote(String text) {
        Mode assetMode = null;
        Mode castMode = null;
        for (Map.Entry<Mode, Mode> st : statements(text)) {
            Mode a = st.getKey();
            Mode c = st.getValue();
            if (a != null) {
                if (assetMode != null && assetMode != a) {
                    return conflictLine("素材", assetMode, a);
                }
                assetMode = a;
            }
            if (c != null) {
                if (castMode != null && castMode != c) {
                    return conflictLine("角色卡", castMode, c);
                }
                castMode = c;
            }
        }
        return null;
    }

    private static String conflictLine(String what, Mode prev, Mode now) {
        return "卡片里「" + what + "模式」有两处互相矛盾的说法（"
                + prev.debugName() + " 与 " + now.debugName() + "），我按最后一条走";
    }

    /** 卡片里有没有提过这一类模式 —— 不看取值（取值缺省也按最宽走），只回答"提没提过"。 */
    private static boolean declares(String text, String[] keys) {
        for (String line : lines(text)) {
            Field field = field(line);
            if (field == null) {
                continue;
            }
            if (keyMatches(field.key(), keys) && valueMode(field.value()) != null) {
                return true;
            }
        }
        return false;
    }

    /** 素材模式声明过没有：没声明过才需要催用户定（章节自检里问这一句）。 */
    public static boolean declaresAsset(String text) {
        return declares(text, ASSET_KEYS);
    }

    /** 角色卡模式声明过没有：决定交接单带不带角色卡模式那一行。 */
    public static boolean declaresCast(String text) {
        return declares(text, CAST_KEYS);
    }

    /** 从卡片里读一类模式（{@code keys} 是这一类认的键名表）：值取到第一个标点为止，带解释的写法也读得出来。 */
    private static Mode parseMode(String text, String[] keys) {
        Mode found = Mode.ALLOW_MISSING;
        for (String line : lines(text)) {
            Field field = field(line);
            if (field == null) {
                continue;
            }
            if (keyMatches(field.key(), keys)) {
                Mode mode = valueMode(field.value());
                if (mode != null) {
                    found = mode;
                }
            }
        }
        return found;
    }

    /**
     * 逐行取出卡片里所有能认出来的模式声明；值认不出就不算声明（认错的代价是校验松紧反了）。
     *
     * <p>每条形如 {@code (素材 or null, 角色卡 or null)}。
     */
    private static List<Map.Entry<Mode, Mode>> statements(String text) {
        List<Map.Entry<Mode, Mode>> out = new ArrayList<>();
        for (String line : lines(text == null ? "" : text)) {
            Field field = field(line);
            if (field == null) {
                continue;
            }
            Mode mode = valueMode(field.value());
            if (mode == null) {
                continue;
            }
            if (keyMatches(field.key(), ASSET_KEYS)) {
                out.add(new AbstractMap.SimpleImmutableEntry<>(mode, null));
            } else if (keyMatches(field.key(), CAST_KEYS)) {
                out.add(new AbstractMap.SimpleImmutableEntry<>(null, mode));
            }
        }
        return out;
    }

    private static final String[] ASSET_KEYS = {"素材", "素材模式", "素材来源"};
    private static final String[] CAST_KEYS = {"角色卡", "角色卡模式", "人物卡", "人物卡模式"};

    /**
     * 认得出的写法 → 取值，一张值表两类模式共用：除了"只用已有"，其余（含全部旧写法）
     * 一律收进 {@code ALLOW_MISSING}，按最宽走。<b>顺序敏感</b>：{@code startsWith} 取第一条命中的。
     */
    private static final Map<String, Mode> VALUES = new LinkedHashMap<>();

    static {
        VALUES.put("只用已有", Mode.ONLY_EXISTING);
        VALUES.put("仅用已有", Mode.ONLY_EXISTING);
        VALUES.put("先预留", Mode.ALLOW_MISSING);
        VALUES.put("之后补充", Mode.ALLOW_MISSING);
        VALUES.put("之后补", Mode.ALLOW_MISSING);
        VALUES.put("零素材", Mode.ALLOW_MISSING);
        VALUES.put("不要素材", Mode.ALLOW_MISSING);
        VALUES.put("不用素材", Mode.ALLOW_MISSING);
        VALUES.put("无素材", Mode.ALLOW_MISSING);
        VALUES.put("允许缺失", Mode.ALLOW_MISSING);
        VALUES.put("之后再加", Mode.ALLOW_MISSING);
        VALUES.put("之后加", Mode.ALLOW_MISSING);
        VALUES.put("允许后补", Mode.ALLOW_MISSING);
    }

    /** 值 → 取值；表里一条都对不上就返回 {@code null}（不按声明算，于是走默认的最宽）。 */
    static Mode valueMode(String value) {
        if (value == null) {
            return null;
        }
        for (Map.Entry<String, Mode> entry : VALUES.entrySet()) {
            if (value.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean keyMatches(String key, String[] names) {
        for (String name : names) {
            if (name.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 一行里有、没有 {@code 键：值}（认列表符号、加粗、反引号与表格行）。 */
    record Field(String key, String value) {
    }

    /** 取一行的 {@code 键：值}：认列表符号、加粗、反引号与表格行（模型这几样都写过）。 */
    static Field field(String line) {
        String raw = line == null ? "" : line.trim();
        if (raw.isEmpty() || raw.startsWith("#")) {
            return null;
        }
        String cleaned = trimStartAny(raw, "-* \t>").trim();
        cleaned = trimMatches(cleaned, "|").trim();
        String keyRaw;
        String rest;
        if (cleaned.indexOf('|') >= 0) {
            List<String> cells = new ArrayList<>();
            for (String cell : cleaned.split("\\|", -1)) {
                String t = cell.trim();
                if (!t.isEmpty()) {
                    cells.add(t);
                }
            }
            if (cells.isEmpty()) {
                return null;
            }
            keyRaw = cells.get(0);
            rest = cells.size() > 1 ? cells.get(1) : "";
        } else {
            int pos = indexOfAny(cleaned, ":：");
            if (pos < 0) {
                return null;
            }
            keyRaw = cleaned.substring(0, pos);
            rest = cleaned.substring(pos);
        }
        String key = stripMarks(keyRaw);
        if (key.endsWith("模式")) {
            key = key.substring(0, key.length() - 2);
        }
        String value = stripMarks(trimStartAny(rest, ":：").trim());
        value = cutAtPunctuation(value);
        return new Field(key, value);
    }

    private static String stripMarks(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isMark(s.charAt(start))) {
            start++;
        }
        while (end > start && isMark(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end).trim();
    }

    private static boolean isMark(char c) {
        return c == '*' || c == '_' || c == '`' || Character.isWhitespace(c);
    }

    private static String cutAtPunctuation(String s) {
        int end = indexOfAny(s, "（(，,；;。：:、");
        return s.substring(0, end < 0 ? s.length() : end).trim();
    }

    /** Rust {@code trim_start_matches(chars)}：去掉开头连续的这些字符。 */
    private static String trimStartAny(String s, String chars) {
        int start = 0;
        while (start < s.length() && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        return s.substring(start);
    }

    /** Rust {@code trim_matches(chars)}：两头都去。 */
    private static String trimMatches(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Rust {@code str::lines()}：按 {@code \n} 切、去掉每行末尾的 {@code \r}；
     * 末尾的换行不产生一个空行。
     */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                String line = text.substring(start, i);
                out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                start = i + 1;
            }
        }
        if (start < text.length()) {
            String line = text.substring(start);
            out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return out;
    }

    /**
     * 这个剧本用不用人物卡（{@code evidence.rs:424-440}）：卡片声明过模式、角色卡羁绊冒险、
     * 或已建 {@code characters/}，决定交接单要不要带角色卡模式那一行。
     */
    public static boolean usesCharacterCards(String scriptKey, Path scriptDir, String constraints) {
        if (declaresCast(constraints)) {
            return true;
        }
        if (scriptKey != null && scriptKey.replace('\\', '/').startsWith("character/")) {
            return true;
        }
        if (scriptDir == null) {
            return false;
        }
        Path characters = scriptDir.resolve("characters");
        if (!Files.isDirectory(characters)) {
            return false;
        }
        try (var entries = Files.list(characters)) {
            return entries.findAny().isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}


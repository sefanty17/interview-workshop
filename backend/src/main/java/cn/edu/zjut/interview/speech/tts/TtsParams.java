package cn.edu.zjut.interview.speech.tts;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取 {@code ttsEngineConfigs[type]} 的工具：那里的值是 {@code Map<String, Object>}，
 * 具体类型随前端写法而变（{@code <input type="number">} 可能给 0 也可能给 "0"），
 * 直接强转会在某一种写法上炸 ClassCastException。
 *
 * <p>为什么允许字符串数字：设置页的 number 输入框在用户清空后拿到的是 {@code ""}，
 * 前端保守起来会把整份配置当字符串存；后端如果只认 {@code Number}，
 * 用户「输了个空格」就会变成 500，而这里退化成默认值就只是「参数没生效」。
 */
public final class TtsParams {

    private TtsParams() {
    }

    /** 字符串参数；null / 空白 → 默认值。返回的字符串已去掉首尾空白（地址/密钥常被误带空格）。 */
    public static String str(Map<String, Object> cfg, String key, String def) {
        Object value = cfg == null ? null : cfg.get(key);
        if (value == null) {
            return def;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? def : text;
    }

    public static int intVal(Map<String, Object> cfg, String key, int def) {
        Double value = number(cfg, key);
        return value == null ? def : (int) Math.round(value);
    }

    public static double dbl(Map<String, Object> cfg, String key, double def) {
        Double value = number(cfg, key);
        return value == null ? def : value;
    }

    /**
     * 布尔参数：认 {@code Boolean}、字符串 "true"/"false"、以及 1/0。
     *
     * <p>1/0 也认：前端的一些开关历史上存过数字，读不出来就会静默变成默认值，
     * 用户看到的「开关拨了没反应」比一个宽松的转换更难受。
     */
    public static boolean bool(Map<String, Object> cfg, String key, boolean def) {
        Object value = cfg == null ? null : cfg.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (value instanceof String s) {
            String text = s.trim().toLowerCase();
            if (text.equals("true") || text.equals("1") || text.equals("yes")) {
                return true;
            }
            if (text.equals("false") || text.equals("0") || text.equals("no")) {
                return false;
            }
        }
        return def;
    }

    private static Double number(Map<String, Object> cfg, String key) {
        Object value = cfg == null ? null : cfg.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 拼「地址 + 端口」，用于前端把 host 与 port 拆成两个字段的引擎（gpt-sovits / vits）。
     *
     * <p>三条规矩，都是为了避免拼出用户看不懂的 URL：
     * <ol>
     *   <li>去掉首尾空白与<b>末尾所有</b>斜杠——用户在地址栏习惯性敲 {@code /}，
     *       不处理就会拼出 {@code http://host//voice/vits}，部分反向代理会 404；</li>
     *   <li>{@code ://} 之后的 authority 里已经有 {@code :} 就认为地址自带端口，原样返回：
     *       否则 {@code http://host:5000} + 9880 会变成 {@code host:5000:9880}；</li>
     *   <li>端口插在 authority 末尾、路径之前：{@code http://host/api} + 9880 →
     *       {@code http://host:9880/api}，而不是 {@code http://host/api:9880}。</li>
     * </ol>
     * {@code port <= 0} 表示「没有端口字段」，只做规整不拼接。
     */
    public static String urlWithPort(String baseUrl, int port) {
        if (baseUrl == null) {
            return "";
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty() || port <= 0) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        int authorityStart = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int slash = url.indexOf('/', authorityStart);
        String authority = slash < 0 ? url.substring(authorityStart) : url.substring(authorityStart, slash);
        if (authority.isEmpty() || authority.contains(":")) {
            return url;
        }
        int insertAt = authorityStart + authority.length();
        return url.substring(0, insertAt) + ":" + port + url.substring(insertAt);
    }

    /** 把成对的 key/value 收进保序 Map（query 参数顺序与上游一致，便于对照日志）。 */
    public static Map<String, String> query(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * 小数值转协议里要的字符串：最多 3 位小数、去掉多余的 0，但保留一位。
     *
     * <p>{@code 1.0} 写成 {@code "1.0"}（上游就是这个字面量）而不是 {@code "1"}：
     * 有些 Python 服务用 {@code isinstance(x, float)} 判断，收到整数会走另一条分支。
     * {@code 1/3} 这种除不尽的保留 3 位，避免把 {@code 0.6666666666666666} 发给上游。
     */
    public static String num(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.3f", value);
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text + "0" : text;
    }
}

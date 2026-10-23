package cn.edu.zjut.interview.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情绪词典，数据来自 {@code classpath:emotions.json}（20 个标准枚举 + 82 条别名）。
 *
 * <p>对应 Rust 侧的 {@code EmotionClassifier}（ONNX）：本项目不做推理，直接用别名表把
 * 模型写出来的情绪词折叠到标准枚举上。既不在别名表也不在枚举里的一律回落「正常」，
 * 否则前端会去找不存在的立绘文件（如 {@code avatars/strict/提醒.webp}）。
 */
public final class EmotionDictionary {

    private static final Logger log = LoggerFactory.getLogger(EmotionDictionary.class);

    /** 词典资源路径（{@code backend/src/main/resources/emotions.json}）。 */
    private static final String RESOURCE = "emotions.json";

    /** 空标签/空词典时的兜底，规格 §4.3。 */
    private static final String DEFAULT_EMOTION = "正常";

    /** 懒加载单例：词典文件不大，但没必要每个 parser 都读一遍。 */
    private static final class Holder {
        private static final EmotionDictionary INSTANCE = new EmotionDictionary();
    }

    private final List<String> emotions;
    private final Map<String, String> aliases;

    public static EmotionDictionary getInstance() {
        return Holder.INSTANCE;
    }

    /** 读取默认资源。 */
    public EmotionDictionary() {
        this(EmotionDictionary.class.getClassLoader().getResourceAsStream(RESOURCE));
    }

    /**
     * 从给定流读取；流为 {@code null} 或解析失败时退化成「只有兜底」的空词典。
     *
     * @param in {@code emotions.json} 内容，方法内负责关闭
     */
    public EmotionDictionary(InputStream in) {
        List<String> emotionList = new ArrayList<>();
        Map<String, String> aliasMap = new LinkedHashMap<>();
        if (in == null) {
            log.warn("[EmotionDictionary] 找不到 {}，仅保留兜底情绪 {}", RESOURCE, DEFAULT_EMOTION);
        } else {
            try (InputStream stream = in) {
                JsonNode root = new ObjectMapper().readTree(stream);
                for (JsonNode node : root.path("emotions")) {
                    emotionList.add(node.asText());
                }
                root.path("aliases").fields().forEachRemaining(e ->
                        aliasMap.put(e.getKey(), e.getValue().asText()));
            } catch (IOException e) {
                log.warn("[EmotionDictionary] 解析 {} 失败，仅保留兜底情绪：{}", RESOURCE, e.getMessage());
            }
        }
        this.emotions = Collections.unmodifiableList(emotionList);
        this.aliases = Collections.unmodifiableMap(aliasMap);
    }

    /**
     * 情绪词 → 标准枚举。
     *
     * @param tag 模型输出的情绪标签，可为 {@code null}
     * @return 空 → {@code "正常"}；命中别名 → 标准枚举；是标准枚举 → 原样；其余 → {@code "正常"}
     */
    public String predict(String tag) {
        if (tag == null || tag.isEmpty()) {
            return DEFAULT_EMOTION;
        }
        String mapped = aliases.get(tag);
        if (mapped != null) {
            return mapped;
        }
        return emotions.contains(tag) ? tag : DEFAULT_EMOTION;
    }

    /** 标准情绪枚举（只读）。 */
    public List<String> emotions() {
        return emotions;
    }

    /** 别名表（只读）。 */
    public Map<String, String> aliases() {
        return aliases;
    }
}

package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.dto.schema.EventSpecView;
import cn.edu.zjut.interview.dto.schema.FieldSpecView;
import cn.edu.zjut.interview.dto.schema.ScriptSchemaView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E1 §9：{@link ScriptSchemaCatalog} 是全编辑器的地基，字段 key 写错前端表单就空，
 * 所以这里把 17 种事件 × 字段顺序全部钉死。期望值逐行对照 {@code script_engine/schema.rs}。
 */
class ScriptSchemaCatalogTest {

    /** 14 种 FieldKind，与 {@code schema.rs::FieldKind} 的 snake_case 取值一致。 */
    private static final Set<String> ALLOWED_KINDS = Set.of(
            "text", "textarea", "number", "bool", "select", "character", "emotion", "chapter",
            "asset", "choice_options", "branch_options", "var_options", "condition", "deprecated");

    /** 字段 key 顺序断言表（规格 §9 原表，逐行对照过 schema.rs）。 */
    private static final Map<String, List<String>> EXPECTED_KEYS = expectedKeys();

    private static final ScriptSchemaView SCHEMA = ScriptSchemaCatalog.build();

    @Test
    void 事件数量是17() {
        assertEquals(17, SCHEMA.events().size());
    }

    @Test
    void 事件typeKey集合精确匹配() {
        Set<String> actual = new java.util.HashSet<>();
        for (EventSpecView event : SCHEMA.events()) {
            actual.add(event.typeKey());
        }
        assertEquals(Set.of(
                "narration", "player", "dialogue", "ai_dialogue", "free_dialogue", "choices", "input",
                "set_variable", "chapter_end", "modify_character", "background", "background_effect",
                "present_pic", "music", "sound", "ambient", "unlock_achievement"), actual);
    }

    @Test
    void 每种事件的字段key顺序与schema_rs一致() {
        Map<String, EventSpecView> byType = byTypeKey();
        assertEquals(EXPECTED_KEYS.keySet(), byType.keySet(), "断言表与 catalog 的事件集合不一致");
        for (Map.Entry<String, List<String>> entry : EXPECTED_KEYS.entrySet()) {
            EventSpecView event = byType.get(entry.getKey());
            assertEquals(entry.getValue(), fieldKeys(event), "事件 " + entry.getKey() + " 的字段顺序不对");
        }
    }

    /**
     * 规格 §9 标题写「六种事件」，实际 catalog 里 {@code prompt} 只出现在三种事件里
     * （{@code free_dialogue} 另有 {@code end_prompt}）；{@code unlock_achievement} 没有。
     * 这里按 catalog 的真实内容断言，差异已写进交付报告的偏差说明。
     */
    @Test
    void prompt字段出现在六种事件里() {
        Set<String> withPrompt = new java.util.TreeSet<>();
        for (EventSpecView event : SCHEMA.events()) {
            if (fieldKeys(event).contains("prompt")) {
                withPrompt.add(event.typeKey());
            }
        }
        assertEquals(Set.of("ai_dialogue", "free_dialogue", "chapter_end"), withPrompt);
        assertFalse(fieldKeys(byTypeKey().get("unlock_achievement")).contains("prompt"),
                "unlock_achievement 不该有 prompt");
        assertTrue(fieldKeys(byTypeKey().get("free_dialogue")).contains("end_prompt"));
    }

    @Test
    void chapter_end_的next是deprecated且disabled() {
        FieldSpecView next = fieldOf(byTypeKey().get("chapter_end"), "next");
        assertEquals("deprecated", next.kind());
        assertFalse(next.enabled());
        assertNotNull(next.hint());
    }

    @Test
    void 背景特效候选项是None加12个内置特效且共13个() {
        FieldSpecView effect = byTypeKey().get("background_effect").fields().get(0);
        assertEquals("effect", effect.key());
        List<String> options = effect.options();
        assertEquals(13, options.size());
        assertEquals("None", options.get(0));
        assertEquals(ScriptSchemaCatalog.KNOWN_EFFECTS, options.subList(1, options.size()));
        assertEquals(12, ScriptSchemaCatalog.KNOWN_EFFECTS.size());
    }

    @Test
    void 每种kind都在14种之内() {
        List<FieldSpecView> all = new ArrayList<>();
        for (EventSpecView event : SCHEMA.events()) {
            all.addAll(event.fields());
        }
        all.addAll(SCHEMA.commonFields());
        all.addAll(SCHEMA.storyConfigFields());
        SCHEMA.unlockConditionTypes().forEach(u -> all.addAll(u.fields()));

        assertFalse(all.isEmpty());
        for (FieldSpecView field : all) {
            assertTrue(ALLOWED_KINDS.contains(field.kind()),
                    "事件字段 " + field.key() + " 的 kind 不在 14 种之内: " + field.kind());
        }
    }

    @Test
    void commonFields是condition加duration() {
        assertEquals(List.of("condition", "duration"), fieldKeys(SCHEMA.commonFields()));
    }

    @Test
    void storyConfigFields是四个() {
        assertEquals(List.of("script_name", "description", "recommand_start", "intro_chapter"),
                fieldKeys(SCHEMA.storyConfigFields()));
    }

    @Test
    void conditionSyntax_note非空() {
        assertNotNull(SCHEMA.conditionSyntax());
        assertNotNull(SCHEMA.conditionSyntax().note());
        assertFalse(SCHEMA.conditionSyntax().note().isBlank());
        assertEquals(List.of("var == 值", "var != 值", "var（真值判断）"), SCHEMA.conditionSyntax().supported());
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static Map<String, EventSpecView> byTypeKey() {
        Map<String, EventSpecView> map = new LinkedHashMap<>();
        for (EventSpecView event : SCHEMA.events()) {
            map.put(event.typeKey(), event);
        }
        return map;
    }

    private static FieldSpecView fieldOf(EventSpecView event, String key) {
        return event.fields().stream()
                .filter(f -> key.equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("事件 " + event.typeKey() + " 没有字段 " + key));
    }

    private static List<String> fieldKeys(EventSpecView event) {
        return fieldKeys(event.fields());
    }

    private static List<String> fieldKeys(List<FieldSpecView> fields) {
        return fields.stream().map(FieldSpecView::key).toList();
    }

    private static Map<String, List<String>> expectedKeys() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("narration", List.of("text", "displayName"));
        map.put("player", List.of("text", "displayName"));
        map.put("dialogue", List.of("character", "text", "emotion", "displayName", "displaySubtitle"));
        map.put("ai_dialogue", List.of("character", "prompt"));
        map.put("free_dialogue", List.of("character", "hint", "max_rounds", "end_line", "prompt", "end_prompt"));
        map.put("choices", List.of("options", "allow_free"));
        map.put("input", List.of("hint"));
        map.put("set_variable", List.of("options"));
        map.put("chapter_end", List.of("end_type", "next_chapter", "options", "prompt", "next"));
        map.put("modify_character", List.of("character", "action", "emotion", "clothes", "perceive"));
        map.put("background", List.of("imagePath", "transition"));
        map.put("background_effect", List.of("effect"));
        map.put("present_pic", List.of("imagePath", "scale"));
        map.put("music", List.of("musicPath", "playbackSpeed"));
        map.put("sound", List.of("soundPath"));
        map.put("ambient", List.of("ambientPath", "volume", "loop", "stop", "fade"));
        map.put("unlock_achievement", List.of("achievement_id", "title", "description"));
        return map;
    }
}

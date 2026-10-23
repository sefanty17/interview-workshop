package cn.edu.zjut.interview.editor;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.dto.ValidationReportView;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.service.ScriptEditorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 E1b §6.1 的 50 个必测用例 + §6.2 的 3 条结构断言。
 *
 * <p>每个用例都自己造剧本数据（不依赖种子剧本），{@code @Transactional} 让每个用例跑完回滚，
 * 用例之间互不影响；test profile 走内存库，不碰 {@code backend/data/interview.mv.db}。
 *
 * <p>命名 {@code case01} … {@code case50} 与规格表格的行号一一对应，方便核对。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScriptValidatorTest {

    @Autowired
    private ScriptEditorService service;

    @Autowired
    private InterviewScriptRepository scriptRepository;

    @Autowired
    private InterviewChapterRepository chapterRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // ============================================================
    // 造数据的小工具
    // ============================================================

    /** 造一个剧本。{@code characters} 固定 {@code ["MAIN"]}，与种子剧本一致。 */
    private InterviewScript script(String code, String name, String intro) {
        InterviewScript s = new InterviewScript();
        s.setCode(code);
        s.setScriptName(name);
        s.setIntroChapter(intro);
        s.setOrderNum(900);
        s.setEnabled(Boolean.TRUE);
        s.setCharacters("[\"MAIN\"]");
        return scriptRepository.save(s);
    }

    /** 正常剧本：一个 intro 章节，linear 直接结束（不产生任何诊断）。 */
    private InterviewScript cleanScript(String code) {
        InterviewScript s = script(code, "剧本 " + code, "main");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        return s;
    }

    /** 往剧本里塞一个章节，{@code events} 逐个传 JSON 片段（不传即空数组）。 */
    private void chapter(InterviewScript s, String code, int order, String... events) {
        InterviewChapter c = new InterviewChapter();
        c.setScriptId(s.getId());
        c.setCode(code);
        c.setName(code);
        c.setOrderNum(order);
        c.setEvents("[" + String.join(",", events) + "]");
        chapterRepository.save(c);
    }

    private ValidationReportView validate(InterviewScript s) {
        return service.validate(s.getCode());
    }

    private static Set<String> codes(ValidationReportView r) {
        return r.diagnostics().stream().map(Diagnostic::code).collect(Collectors.toSet());
    }

    private static boolean has(ValidationReportView r, String code) {
        return codes(r).contains(code);
    }

    private static Diagnostic one(ValidationReportView r, String code) {
        return r.diagnostics().stream()
                .filter(d -> d.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "期望出现诊断 " + code + "，实际是 " + codes(r)));
    }

    private static void assertHas(ValidationReportView r, String code) {
        assertTrue(has(r, code), "期望出现诊断 " + code + "，实际是 " + codes(r));
    }

    private static void assertNo(ValidationReportView r, String code) {
        assertFalse(has(r, code), "不该出现诊断 " + code + "，实际是 " + codes(r));
    }

    private static void assertSeverity(ValidationReportView r, String code, Severity expected) {
        assertEquals(expected, one(r, code).severity(), code + " 的 severity 不对");
    }

    // ============================================================
    // 剧本级（#1 - #4）
    // ============================================================

    @Test
    @DisplayName("#1 空章节列表 → chapters.empty(error)")
    void case01() {
        InterviewScript s = script("vt01", "剧本 vt01", "main");
        ValidationReportView r = validate(s);
        assertHas(r, "chapters.empty");
        assertSeverity(r, "chapters.empty", Severity.ERROR);
        assertTrue(r.edges().isEmpty(), "没有章节就没有边");
        assertTrue(r.variables().isEmpty(), "没有章节就没有变量");
    }

    @Test
    @DisplayName("#2 intro_chapter 指向不存在的章节 → config.intro_missing(error)")
    void case02() {
        InterviewScript s = script("vt02", "剧本 vt02", "ghost");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "config.intro_missing");
        assertSeverity(r, "config.intro_missing", Severity.ERROR);
        assertTrue(one(r, "config.intro_missing").message().contains("ghost"));
    }

    @Test
    @DisplayName("#3 script_name 为空 → config.no_script_name(warn)")
    void case03() {
        InterviewScript s = cleanScript("vt03");
        s.setScriptName("");
        scriptRepository.save(s);
        ValidationReportView r = validate(s);
        assertHas(r, "config.no_script_name");
        assertSeverity(r, "config.no_script_name", Severity.WARN);
        // 原版文案里带目录名，本项目目录名 = code
        assertTrue(one(r, "config.no_script_name").message().contains("vt03"));
    }

    @Test
    @DisplayName("#4 两个剧本 script_name 相同 → config.duplicate_name(error)")
    void case04() {
        InterviewScript a = cleanScript("vt04a");
        InterviewScript b = cleanScript("vt04b");
        a.setScriptName("重名剧本VT04");
        b.setScriptName("重名剧本VT04");
        scriptRepository.save(a);
        scriptRepository.save(b);

        ValidationReportView r = validate(a);
        assertHas(r, "config.duplicate_name");
        assertSeverity(r, "config.duplicate_name", Severity.ERROR);
        assertTrue(one(r, "config.duplicate_name").message().contains("vt04b"));
    }

    // ============================================================
    // 章节 / 事件骨架（#5 - #10）
    // ============================================================

    @Test
    @DisplayName("#5 章节 events 为空数组 → chapter.no_events(warn) + chapter.no_end(error)")
    void case05() {
        InterviewScript s = script("vt05", "剧本 vt05", "main");
        chapter(s, "main", 1);
        ValidationReportView r = validate(s);
        assertHas(r, "chapter.no_events");
        assertSeverity(r, "chapter.no_events", Severity.WARN);
        assertHas(r, "chapter.no_end");
        assertSeverity(r, "chapter.no_end", Severity.ERROR);
    }

    @Test
    @DisplayName("#6 events 含 \"abc\"（不是对象）→ event.not_a_map(error)")
    void case06() {
        InterviewScript s = script("vt06", "剧本 vt06", "main");
        chapter(s, "main", 1, "\"abc\"");
        ValidationReportView r = validate(s);
        assertHas(r, "event.not_a_map");
        assertSeverity(r, "event.not_a_map", Severity.ERROR);
        assertEquals(0, one(r, "event.not_a_map").eventIndex());
    }

    @Test
    @DisplayName("#7 events 含 {\"text\":\"x\"} → event.missing_type(error)")
    void case07() {
        InterviewScript s = script("vt07", "剧本 vt07", "main");
        chapter(s, "main", 1, "{\"text\":\"x\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "event.missing_type");
        assertSeverity(r, "event.missing_type", Severity.ERROR);
    }

    @Test
    @DisplayName("#8 events 含 {\"type\":\"nope\"} → event.unknown_type(error)")
    void case08() {
        InterviewScript s = script("vt08", "剧本 vt08", "main");
        chapter(s, "main", 1, "{\"type\":\"nope\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "event.unknown_type");
        assertSeverity(r, "event.unknown_type", Severity.ERROR);
        assertTrue(one(r, "event.unknown_type").message().contains("nope"));
    }

    @Test
    @DisplayName("#9 {\"type\":\"narration\"} → field.required_missing(error)，field=text")
    void case09() {
        InterviewScript s = script("vt09", "剧本 vt09", "main");
        chapter(s, "main", 1, "{\"type\":\"narration\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "field.required_missing");
        assertSeverity(r, "field.required_missing", Severity.ERROR);
        assertEquals("text", one(r, "field.required_missing").field());
        assertEquals("narration 缺少必填字段「旁白文本」", one(r, "field.required_missing").message());
    }

    @Test
    @DisplayName("#10 ai_dialogue 带拼错字段 → field.unknown(warn)，character 有默认值不报必填")
    void case10() {
        InterviewScript s = script("vt10", "剧本 vt10", "main");
        chapter(s, "main", 1,
                "{\"type\":\"ai_dialogue\",\"prompt\":\"x\",\"typo\":1}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "field.unknown");
        assertSeverity(r, "field.unknown", Severity.WARN);
        assertEquals("typo", one(r, "field.unknown").field());
        assertNo(r, "field.required_missing");
    }

    // ============================================================
    // 条件（#11 - #13）
    // ============================================================

    @Test
    @DisplayName("#11 condition \"hp >= 5\" → condition.unsupported_operator(error)")
    void case11() {
        InterviewScript s = script("vt11", "剧本 vt11", "main");
        chapter(s, "main", 1,
                "{\"type\":\"narration\",\"text\":\"x\",\"condition\":\"hp >= 5\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "condition.unsupported_operator");
        assertSeverity(r, "condition.unsupported_operator", Severity.ERROR);
        assertTrue(one(r, "condition.unsupported_operator").message().contains(">="));
    }

    @Test
    @DisplayName("#12 condition \"my var == 1\" → condition.bad_variable(error)")
    void case12() {
        InterviewScript s = script("vt12", "剧本 vt12", "main");
        chapter(s, "main", 1,
                "{\"type\":\"narration\",\"text\":\"x\",\"condition\":\"my var == 1\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "condition.bad_variable");
        assertSeverity(r, "condition.bad_variable", Severity.ERROR);
    }

    @Test
    @DisplayName("#13 condition \"flag == %player%\" → condition.placeholder_not_replaced(warn)")
    void case13() {
        InterviewScript s = script("vt13", "剧本 vt13", "main");
        chapter(s, "main", 1,
                "{\"type\":\"narration\",\"text\":\"x\",\"condition\":\"flag == %player%\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "condition.placeholder_not_replaced");
        assertSeverity(r, "condition.placeholder_not_replaced", Severity.WARN);
    }

    // ============================================================
    // choices（#14 - #19）
    // ============================================================

    @Test
    @DisplayName("#14 {\"type\":\"choices\",\"options\":[]} → choices.empty(error)")
    void case14() {
        InterviewScript s = script("vt14", "剧本 vt14", "main");
        chapter(s, "main", 1,
                "{\"type\":\"choices\",\"options\":[]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "choices.empty");
        assertSeverity(r, "choices.empty", Severity.ERROR);
    }

    @Test
    @DisplayName("#15 两个选项文案都是 \"A\" → choices.duplicate_text(warn)")
    void case15() {
        InterviewScript s = script("vt15", "剧本 vt15", "main");
        chapter(s, "main", 1,
                "{\"type\":\"choices\",\"options\":[{\"text\":\"A\"},{\"text\":\"A\"}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "choices.duplicate_text");
        assertSeverity(r, "choices.duplicate_text", Severity.WARN);
    }

    @Test
    @DisplayName("#16 中间选项 {} 且无 condition → choices.catch_all_not_last(warn)")
    void case16() {
        InterviewScript s = script("vt16", "剧本 vt16", "main");
        chapter(s, "main", 1,
                "{\"type\":\"choices\",\"options\":[{\"text\":\"A\"},{},{\"text\":\"C\"}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "choices.catch_all_not_last");
        assertSeverity(r, "choices.catch_all_not_last", Severity.WARN);
        assertNo(r, "choices.catch_all_conditional");
    }

    @Test
    @DisplayName("#17 中间选项 {\"condition\":\"f\"} 无文案 → choices.catch_all_conditional(info)")
    void case17() {
        InterviewScript s = script("vt17", "剧本 vt17", "main");
        chapter(s, "main", 1,
                "{\"type\":\"choices\",\"options\":[{\"text\":\"A\"},{\"condition\":\"f\"},{\"text\":\"C\"}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "choices.catch_all_conditional");
        assertSeverity(r, "choices.catch_all_conditional", Severity.INFO);
        assertNo(r, "choices.catch_all_not_last");
    }

    @Test
    @DisplayName("#18 选项带 next → choices.option_next_ignored(error)")
    void case18() {
        InterviewScript s = script("vt18", "剧本 vt18", "main");
        chapter(s, "main", 1,
                "{\"type\":\"choices\",\"options\":[{\"text\":\"A\",\"next\":\"main\"}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "choices.option_next_ignored");
        assertSeverity(r, "choices.option_next_ignored", Severity.ERROR);
    }

    @Test
    @DisplayName("#19 选项带 lock_hint 无条件 → choices.lock_hint_without_condition(info)")
    void case19() {
        InterviewScript s = script("vt19", "剧本 vt19", "main");
        chapter(s, "main", 1,
                "{\"type\":\"choices\",\"options\":[{\"text\":\"A\",\"lock_hint\":\"好感不足\"}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "choices.lock_hint_without_condition");
        assertSeverity(r, "choices.lock_hint_without_condition", Severity.INFO);
    }

    // ============================================================
    // set_variable / actions（#20 - #25）
    // ============================================================

    @Test
    @DisplayName("#20 {\"type\":\"set_variable\"} → set_variable.no_options(error)")
    void case20() {
        InterviewScript s = script("vt20", "剧本 vt20", "main");
        chapter(s, "main", 1,
                "{\"type\":\"set_variable\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "set_variable.no_options");
        assertSeverity(r, "set_variable.no_options", Severity.ERROR);
    }

    @Test
    @DisplayName("#21 {\"type\":\"set_variable\",\"options\":[]} → set_variable.empty(warn)")
    void case21() {
        InterviewScript s = script("vt21", "剧本 vt21", "main");
        chapter(s, "main", 1,
                "{\"type\":\"set_variable\",\"options\":[]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "set_variable.empty");
        assertSeverity(r, "set_variable.empty", Severity.WARN);
    }

    @Test
    @DisplayName("#22 action set_var \"bad expr!\" → action.bad_expression(error)")
    void case22() {
        InterviewScript s = script("vt22", "剧本 vt22", "main");
        chapter(s, "main", 1,
                "{\"type\":\"set_variable\",\"options\":[{\"actions\":"
                        + "[{\"type\":\"set_var\",\"content\":\"bad expr!\"}]}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "action.bad_expression");
        assertSeverity(r, "action.bad_expression", Severity.ERROR);
    }

    @Test
    @DisplayName("#23 action set_var 只有 name/value → action.legacy_shape(error)")
    void case23() {
        InterviewScript s = script("vt23", "剧本 vt23", "main");
        chapter(s, "main", 1,
                "{\"type\":\"set_variable\",\"options\":[{\"actions\":"
                        + "[{\"type\":\"set_var\",\"name\":\"a\",\"value\":\"1\"}]}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "action.legacy_shape");
        assertSeverity(r, "action.legacy_shape", Severity.ERROR);
        assertNo(r, "action.empty_expression");
    }

    @Test
    @DisplayName("#24 set_variable 里的 add_line → action.not_supported_here(warn)，无 empty_content")
    void case24() {
        InterviewScript s = script("vt24", "剧本 vt24", "main");
        chapter(s, "main", 1,
                "{\"type\":\"set_variable\",\"options\":[{\"actions\":"
                        + "[{\"type\":\"add_line\",\"content\":\"x\"}]}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "action.not_supported_here");
        assertSeverity(r, "action.not_supported_here", Severity.WARN);
        assertNo(r, "action.empty_content");
    }

    @Test
    @DisplayName("#25 action {\"type\":\"weird\"} → action.unknown_type(warn)")
    void case25() {
        InterviewScript s = script("vt25", "剧本 vt25", "main");
        chapter(s, "main", 1,
                "{\"type\":\"choices\",\"options\":[{\"text\":\"A\",\"actions\":[{\"type\":\"weird\"}]}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "action.unknown_type");
        assertSeverity(r, "action.unknown_type", Severity.WARN);
    }

    // ============================================================
    // 其余事件类型（#26 - #33）
    // ============================================================

    @Test
    @DisplayName("#26 free_dialogue 无出口 → free_dialogue.no_exit(error)")
    void case26() {
        InterviewScript s = script("vt26", "剧本 vt26", "main");
        chapter(s, "main", 1,
                "{\"type\":\"free_dialogue\",\"max_rounds\":0,\"end_line\":\"\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "free_dialogue.no_exit");
        assertSeverity(r, "free_dialogue.no_exit", Severity.ERROR);
    }

    @Test
    @DisplayName("#27 effect \"starfield\" → effect.case(info)，无 effect.unknown")
    void case27() {
        InterviewScript s = script("vt27", "剧本 vt27", "main");
        chapter(s, "main", 1,
                "{\"type\":\"background_effect\",\"effect\":\"starfield\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "effect.case");
        assertSeverity(r, "effect.case", Severity.INFO);
        assertNo(r, "effect.unknown");
        assertTrue(one(r, "effect.case").message().contains("StarField"));
    }

    @Test
    @DisplayName("#28 effect \"Nope\" → effect.unknown(warn)")
    void case28() {
        InterviewScript s = script("vt28", "剧本 vt28", "main");
        chapter(s, "main", 1,
                "{\"type\":\"background_effect\",\"effect\":\"Nope\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "effect.unknown");
        assertSeverity(r, "effect.unknown", Severity.WARN);
        assertNo(r, "effect.case");
    }

    @Test
    @DisplayName("#29 music 素材不存在 → asset.missing(warn)")
    void case29() {
        InterviewScript s = script("vt29", "剧本 vt29", "main");
        chapter(s, "main", 1,
                "{\"type\":\"music\",\"musicPath\":\"a.mp3\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "asset.missing");
        assertSeverity(r, "asset.missing", Severity.WARN);
        Diagnostic d = one(r, "asset.missing");
        assertEquals("musicPath", d.field());
        assertEquals("找不到素材「a.mp3」。运行时不会报错，只会静默把画面/声音清空", d.message());
    }

    @Test
    @DisplayName("#30 ambient 无 path 无 stop → ambient.no_path(warn)")
    void case30() {
        InterviewScript s = script("vt30", "剧本 vt30", "main");
        chapter(s, "main", 1,
                "{\"type\":\"ambient\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "ambient.no_path");
        assertSeverity(r, "ambient.no_path", Severity.WARN);
        assertEquals("ambientPath", one(r, "ambient.no_path").field());
    }

    @Test
    @DisplayName("#31 ambient stop=true → 无 ambient.no_path、无 asset.missing")
    void case31() {
        InterviewScript s = script("vt31", "剧本 vt31", "main");
        chapter(s, "main", 1,
                "{\"type\":\"ambient\",\"stop\":true}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertNo(r, "ambient.no_path");
        assertNo(r, "asset.missing");
    }

    @Test
    @DisplayName("#32 music playbackSpeed=5 → music.bad_speed(warn)")
    void case32() {
        InterviewScript s = script("vt32", "剧本 vt32", "main");
        chapter(s, "main", 1,
                "{\"type\":\"music\",\"musicPath\":\"a.mp3\",\"playbackSpeed\":5}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "music.bad_speed");
        assertSeverity(r, "music.bad_speed", Severity.WARN);
        assertEquals("playbackSpeed", one(r, "music.bad_speed").field());
        // Rust Display 对 5.0 输出 "5"，不是 "5.0"
        assertEquals("播放速度 5 超出建议范围（0–4）。≤0 无效，>2 通常会失真",
                one(r, "music.bad_speed").message());
    }

    @Test
    @DisplayName("#33 modify_character action=dance → character.action_unknown(warn)")
    void case33() {
        InterviewScript s = script("vt33", "剧本 vt33", "main");
        chapter(s, "main", 1,
                "{\"type\":\"modify_character\",\"character\":\"MAIN\",\"action\":\"dance\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "character.action_unknown");
        assertSeverity(r, "character.action_unknown", Severity.WARN);
        assertEquals("action", one(r, "character.action_unknown").field());
    }

    // ============================================================
    // chapter_end（#34 - #46）
    // ============================================================

    @Test
    @DisplayName("#34 end_type=weird → chapter_end.unknown_end_type(error)")
    void case34() {
        InterviewScript s = script("vt34", "剧本 vt34", "main");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"weird\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.unknown_end_type");
        assertSeverity(r, "chapter_end.unknown_end_type", Severity.ERROR);
        assertEquals("end_type", one(r, "chapter_end.unknown_end_type").field());
    }

    @Test
    @DisplayName("#35 linear next_chapter=ghost → chapter_end.dangling(error)，无 edge")
    void case35() {
        InterviewScript s = script("vt35", "剧本 vt35", "main");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"ghost\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.dangling");
        assertSeverity(r, "chapter_end.dangling", Severity.ERROR);
        assertTrue(r.edges().stream().noneMatch(e -> "ghost".equals(e.to())), "不该为不存在的章节建边");
        assertTrue(r.edges().isEmpty(), "悬空目标不产生任何边");
    }

    @Test
    @DisplayName("#36 linear next=end.yaml → chapter_end.end_suffix(error)")
    void case36() {
        InterviewScript s = script("vt36", "剧本 vt36", "main");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next\":\"end.yaml\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.end_suffix");
        assertSeverity(r, "chapter_end.end_suffix", Severity.ERROR);
        assertTrue(r.edges().isEmpty(), "end_suffix 之后 return，不 push edge");
        assertTrue(one(r, "chapter_end.end_suffix").message().contains("Chapters/end.yaml"));
    }

    @Test
    @DisplayName("#37 linear 没有 next → chapter_end.no_next(warn)")
    void case37() {
        InterviewScript s = script("vt37", "剧本 vt37", "main");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.no_next");
        assertSeverity(r, "chapter_end.no_next", Severity.WARN);
        assertTrue(r.edges().isEmpty());
    }

    @Test
    @DisplayName("#38 next 与 next_chapter 同时存在 → chapter_end.both_next_fields(warn)")
    void case38() {
        InterviewScript s = script("vt38", "剧本 vt38", "main");
        chapter(s, "second", 2, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next\":\"second\",\"next_chapter\":\"second\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.both_next_fields");
        assertSeverity(r, "chapter_end.both_next_fields", Severity.WARN);
        // 两个都有时按 next 走，边只建一次
        assertEquals(1, r.edges().stream().filter(e -> "main".equals(e.from())).count());
    }

    @Test
    @DisplayName("#39 chapter_end 不在最后 → chapter_end.not_last(warn)")
    void case39() {
        InterviewScript s = script("vt39", "剧本 vt39", "main");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}",
                "{\"type\":\"narration\",\"text\":\"x\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.not_last");
        assertSeverity(r, "chapter_end.not_last", Severity.WARN);
        assertTrue(one(r, "chapter_end.not_last").message().contains("还有 1 个事件"));
    }

    @Test
    @DisplayName("#40 branching 分支无条件 → branch_no_condition(warn)，无 no_options")
    void case40() {
        InterviewScript s = script("vt40", "剧本 vt40", "main");
        chapter(s, "a", 2, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"branching\",\"options\":[{\"next\":\"a\"}]}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.branch_no_condition");
        assertSeverity(r, "chapter_end.branch_no_condition", Severity.WARN);
        assertNo(r, "chapter_end.no_options");
    }

    @Test
    @DisplayName("#41 branching 无 options → chapter_end.no_options(error)")
    void case41() {
        InterviewScript s = script("vt41", "剧本 vt41", "main");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"branching\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.no_options");
        assertSeverity(r, "chapter_end.no_options", Severity.ERROR);
    }

    @Test
    @DisplayName("#42 branching 分支无 default → chapter_end.no_default_branch(warn)")
    void case42() {
        InterviewScript s = script("vt42", "剧本 vt42", "main");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"branching\",\"options\":"
                        + "[{\"condition\":\"flag\",\"next\":\"end\"},{\"condition\":\"other\",\"next\":\"end\"}]}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.no_default_branch");
        assertSeverity(r, "chapter_end.no_default_branch", Severity.WARN);
    }

    @Test
    @DisplayName("#43 ai_judged 分支无 name → chapter_end.ai_option_no_name(warn)")
    void case43() {
        InterviewScript s = script("vt43", "剧本 vt43", "main");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"ai_judged\",\"options\":[{\"next\":\"end\"}]}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.ai_option_no_name");
        assertSeverity(r, "chapter_end.ai_option_no_name", Severity.WARN);
    }

    @Test
    @DisplayName("#44 ai_judged default 分支无 name → 无 ai_option_no_name")
    void case44() {
        InterviewScript s = script("vt44", "剧本 vt44", "main");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"ai_judged\",\"options\":"
                        + "[{\"default\":true,\"next\":\"end\"}]}");
        ValidationReportView r = validate(s);
        assertNo(r, "chapter_end.ai_option_no_name");
        assertNo(r, "chapter_end.no_default_branch");
    }

    @Test
    @DisplayName("#45 ai_judged 分支带 condition → ai_condition_ignored(info)")
    void case45() {
        InterviewScript s = script("vt45", "剧本 vt45", "main");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"ai_judged\",\"options\":"
                        + "[{\"name\":\"deep\",\"condition\":\"flag == 1\",\"next\":\"end\"}]}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.ai_condition_ignored");
        assertSeverity(r, "chapter_end.ai_condition_ignored", Severity.INFO);
        assertEquals("condition", one(r, "chapter_end.ai_condition_ignored").field());
    }

    @Test
    @DisplayName("#46 分支同时有 text → chapter_end.choice_shaped_option(error)")
    void case46() {
        InterviewScript s = script("vt46", "剧本 vt46", "main");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"branching\",\"options\":"
                        + "[{\"condition\":\"flag\",\"next\":\"end\",\"text\":\"选 A\"}]}");
        ValidationReportView r = validate(s);
        assertHas(r, "chapter_end.choice_shaped_option");
        assertSeverity(r, "chapter_end.choice_shaped_option", Severity.ERROR);
    }

    // ============================================================
    // 章节图（#47 - #48）
    // ============================================================

    @Test
    @DisplayName("#47 a→b→a 循环 → graph.cycle(warn)，消息含 \"a → b → a\"")
    void case47() {
        InterviewScript s = script("vt47", "剧本 vt47", "a");
        chapter(s, "a", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"b\"}");
        chapter(s, "b", 2, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"a\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "graph.cycle");
        assertSeverity(r, "graph.cycle", Severity.WARN);
        assertEquals("章节之间存在循环：a → b → a。引擎没有循环检测，玩家可能被困在里面出不来",
                one(r, "graph.cycle").message());
    }

    @Test
    @DisplayName("#48 孤立章节 → graph.unreachable(warn)")
    void case48() {
        InterviewScript s = script("vt48", "剧本 vt48", "main");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        chapter(s, "orphan", 2, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "graph.unreachable");
        assertSeverity(r, "graph.unreachable", Severity.WARN);
        Diagnostic d = one(r, "graph.unreachable");
        assertEquals("orphan", d.chapter());
        assertEquals("章节「orphan」没有任何章节指向它，玩家永远走不到", d.message());
    }

    // ============================================================
    // 变量（#49 - #50）
    // ============================================================

    @Test
    @DisplayName("#49 变量只读 → variable.never_set(warn)，variables 含 flag")
    void case49() {
        InterviewScript s = script("vt49", "剧本 vt49", "main");
        chapter(s, "main", 1,
                "{\"type\":\"narration\",\"text\":\"x\",\"condition\":\"flag\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "variable.never_set");
        assertSeverity(r, "variable.never_set", Severity.WARN);
        assertEquals(List.of("flag"), r.variables());
        assertTrue(one(r, "variable.never_set").message().contains("flag"));
        assertNo(r, "variable.never_read");
    }

    @Test
    @DisplayName("#50 变量只写 → variable.never_read(info)")
    void case50() {
        InterviewScript s = script("vt50", "剧本 vt50", "main");
        chapter(s, "main", 1,
                "{\"type\":\"set_variable\",\"options\":[{\"actions\":"
                        + "[{\"type\":\"set_var\",\"content\":\"count = 1\"}]}]}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);
        assertHas(r, "variable.never_read");
        assertSeverity(r, "variable.never_read", Severity.INFO);
        assertEquals(List.of("count"), r.variables());
        assertNo(r, "variable.never_set");
    }

    // ============================================================
    // §6.2 结构断言
    // ============================================================

    @Test
    @DisplayName("结构断言：error 全在 warn 前，warn 全在 info 前")
    void structureOrdering() {
        InterviewScript s = script("vtS1", "剧本 vtS1", "main");
        chapter(s, "main", 1,
                // error：缺 text
                "{\"type\":\"narration\"}",
                // warn：拼错字段
                "{\"type\":\"narration\",\"text\":\"x\",\"typo\":1}",
                // info：大小写不对的特效
                "{\"type\":\"background_effect\",\"effect\":\"starfield\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validate(s);

        List<Severity> sevs = r.diagnostics().stream().map(Diagnostic::severity).toList();
        for (int i = 1; i < sevs.size(); i++) {
            assertTrue(sevs.get(i - 1).rank() <= sevs.get(i).rank(),
                    "排序被打乱：" + sevs);
        }
        assertEquals(Severity.ERROR, sevs.get(0));
        assertEquals(Severity.INFO, sevs.get(sevs.size() - 1));
        assertEquals(r.diagnostics().size(),
                r.errorCount() + r.warnCount() + r.infoCount(), "三个计数之和应等于诊断总数");
    }

    @Test
    @DisplayName("结构断言：linear 到 end 的边 isEnd=true，到真实章节 isEnd=false")
    void structureEdges() {
        InterviewScript s = script("vtS2", "剧本 vtS2", "main");
        chapter(s, "second", 2, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        chapter(s, "main", 1, "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"second\"}");
        ValidationReportView r = validate(s);

        ChapterEdge toSecond = r.edges().stream().filter(e -> "main".equals(e.from())).findFirst().orElseThrow();
        assertEquals("second", toSecond.to());
        assertFalse(toSecond.isEnd());
        assertEquals("", toSecond.label(), "linear 的边没有标签");
        assertEquals("linear", toSecond.endType());

        ChapterEdge toEnd = r.edges().stream().filter(e -> "second".equals(e.from())).findFirst().orElseThrow();
        assertEquals("end", toEnd.to());
        assertTrue(toEnd.isEnd());
    }

    @Test
    @DisplayName("结构断言：branching 的 edge label 取 condition → name → 「默认」")
    void structureBranchLabels() {
        InterviewScript s = script("vtS3", "剧本 vtS3", "main");
        chapter(s, "main", 1,
                "{\"type\":\"chapter_end\",\"end_type\":\"branching\",\"options\":["
                        + "{\"condition\":\"flag == 1\",\"next\":\"end\"},"
                        + "{\"name\":\"温柔\",\"next\":\"end\"},"
                        + "{\"default\":true,\"next\":\"end\"}]}");
        ValidationReportView r = validate(s);

        List<String> labels = r.edges().stream().map(ChapterEdge::label).toList();
        assertEquals(List.of("flag == 1", "温柔", "默认"), labels);
        assertTrue(r.edges().stream().allMatch(ChapterEdge::isEnd));
        assertTrue(r.edges().stream().allMatch(e -> "branching".equals(e.endType())));
    }

    // ============================================================
    // 规格 §4.1：6 个码本项目永不产生
    // ============================================================

    @Test
    @DisplayName("§4.1 干净剧本不产生也不应产生那 6 个码，且非法章节的 6 个码定义为常量")
    void notProducedCodesAreDocumented() {
        InterviewScript s = cleanScript("vtN1");
        ValidationReportView r = validate(s);
        assertEquals(List.of(), r.diagnostics(), "干净剧本不该有任何诊断");
        assertEquals(0, r.errorCount());
        assertEquals(0, r.warnCount());
        assertEquals(0, r.infoCount());
        assertNotNull(ScriptValidator.NOT_PRODUCED_CODES);
        assertEquals(6, ScriptValidator.NOT_PRODUCED_CODES.size());
        for (String code : ScriptValidator.NOT_PRODUCED_CODES) {
            assertNo(r, code);
        }
    }

    // ============================================================
    // §4.5 变量表达式解析（含 random 的整数/区间校验）
    // ============================================================

    @Test
    @DisplayName("§4.5 parseVariableAction：合法/非法表达式")
    void variableActionParsing() {
        assertEquals("flag", ScriptValidator.parseVariableAction("flag = true").orElseThrow());
        assertEquals("count", ScriptValidator.parseVariableAction(" count += 1 ").orElseThrow());
        assertEquals("hp", ScriptValidator.parseVariableAction("hp -= 5").orElseThrow());
        assertEquals("luck", ScriptValidator.parseVariableAction("luck = random(1, 6)").orElseThrow());
        // random 的区间反了 → 原版 parse_value 会 Err
        assertTrue(ScriptValidator.parseVariableAction("luck = random(6, 1)").isEmpty());
        // random 的参数不是整数 → 原版 parse_value 会 Err
        assertTrue(ScriptValidator.parseVariableAction("luck = random(a, b)").isEmpty());
        assertTrue(ScriptValidator.parseVariableAction("bad expr!").isEmpty());
        // 四个运算符里只有 = += -= 合法
        assertTrue(ScriptValidator.parseVariableAction("hp *= 2").isEmpty());
    }

    @Test
    @DisplayName("§4.4 素材：同主干换扩展名能命中，并报 asset.extension_mismatch(info)")
    void assetExtensionMismatch(@TempDir java.nio.file.Path tempDir) throws Exception {
        // 造真实的扁平素材目录：<assetTarget>/<code>/cover.png。
        // 用临时目录构造一个校验器，不往仓库里的 frontend/ 写任何东西。
        java.nio.file.Path dir = tempDir.resolve("vtAs");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve("cover.png"), new byte[]{1});

        ScriptValidator validator = new ScriptValidator(scriptRepository, objectMapper, tempDir.toString());

        InterviewScript s = script("vtAs", "剧本 vtAs", "main");
        chapter(s, "main", 1,
                "{\"type\":\"background\",\"imagePath\":\"cover.jpg\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r = validator.validate(s,
                chapterRepository.findByScriptIdOrderByOrderNumAsc(s.getId()));
        assertNo(r, "asset.missing");
        assertHas(r, "asset.extension_mismatch");
        assertSeverity(r, "asset.extension_mismatch", Severity.INFO);
        assertTrue(one(r, "asset.extension_mismatch").message().contains("cover.png"));

        // 精确命中（忽略大小写）时不报任何素材诊断
        java.nio.file.Path dir2 = tempDir.resolve("vtAs2");
        java.nio.file.Files.createDirectories(dir2);
        java.nio.file.Files.write(dir2.resolve("cover.png"), new byte[]{1});
        InterviewScript s2 = script("vtAs2", "剧本 vtAs2", "main");
        chapter(s2, "main", 1,
                "{\"type\":\"background\",\"imagePath\":\"COVER.PNG\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r2 = validator.validate(s2,
                chapterRepository.findByScriptIdOrderByOrderNumAsc(s2.getId()));
        assertNo(r2, "asset.missing");
        assertNo(r2, "asset.extension_mismatch");

        // 素材目录里只有 png，引用 mp3 时不会被同主干兜底命中（类别不同）
        InterviewScript s3 = script("vtAs3", "剧本 vtAs3", "main");
        java.nio.file.Files.createDirectories(tempDir.resolve("vtAs3"));
        java.nio.file.Files.write(tempDir.resolve("vtAs3").resolve("cover.png"), new byte[]{1});
        chapter(s3, "main", 1,
                "{\"type\":\"music\",\"musicPath\":\"cover.mp3\"}",
                "{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}");
        ValidationReportView r3 = validator.validate(s3,
                chapterRepository.findByScriptIdOrderByOrderNumAsc(s3.getId()));
        assertHas(r3, "asset.missing");
        assertNo(r3, "asset.extension_mismatch");
    }
}

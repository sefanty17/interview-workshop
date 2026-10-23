package cn.edu.zjut.interview.importer;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规格 B5-BE §四 的 6 个用例：占位符、粒子中文名、资源映射命中/未命中、
 * 悬空章节、未知事件类型、重复导入不翻倍。
 *
 * <p>跑在独立内存库上（{@code script-import-test}），避免导入的剧本污染
 * {@code ScriptControllerTest} 里"列表恰好 4 个"的断言。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:script-import-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        // 剧本自带资源落到构建目录，别污染 frontend/public
        "app.import.asset-target=target/test-assets/backgrounds/scripts"
})
@ActiveProfiles("test")
class ScriptYamlImporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ScriptYamlImporter importer;

    @Autowired
    private InterviewScriptRepository scriptRepository;

    @Autowired
    private InterviewChapterRepository chapterRepository;

    @Autowired
    private cn.edu.zjut.interview.repository.InterviewerRepository interviewerRepository;

    @TempDir
    Path tempDir;

    // ---------- 1. 占位符 ----------

    @Test
    void replacesPlayerAndCharPlaceholders() throws Exception {
        Path dir = script("placeholder-case", """
                name: 占位符章节
                events:
                  - type: narration
                    text: "%player% 看着 %char% 发呆"
                """);

        ImportResult result = importer.importDirectory(dir);
        String events = chaptersJson(result.scriptCode());

        assertTrue(events.contains("{user}"), "正文里的 %player% 应换成 {user}：" + events);
        assertTrue(events.contains("{char}"), "正文里的 %char% 应换成 {char}：" + events);
        assertFalse(events.contains("%player%"), "不该残留 %player%：" + events);
        assertFalse(events.contains("%char%"), "不该残留 %char%：" + events);
    }

    // ---------- 2. 粒子中文名 ----------

    @Test
    void mapsChineseEffectNameToEnglishKey() throws Exception {
        Path dir = script("effect-case", """
                name: 特效章节
                events:
                  - type: background_effect
                    effect: 星空
                  - type: background_effect
                    effect: Starfield
                """);

        ImportResult result = importer.importDirectory(dir);
        JsonNode events = events(result.scriptCode());

        assertEquals("starfield", events.get(0).path("effect").asText(), "中文名 星空 → starfield");
        assertEquals("starfield", events.get(1).path("effect").asText(), "Starfield 大小写归一 → starfield");
        assertEquals(2, result.report().green().size(), "两条都应算可执行");
    }

    // ---------- 3. 资源映射：命中 green / 未命中 yellow 且不改写 ----------

    @Test
    void mapsKnownBackgroundAndReportsUnknownAsYellow() throws Exception {
        Path dir = script("background-case", """
                name: 背景章节
                events:
                  - type: background
                    imagePath: 夜晚.webp
                  - type: background
                    imagePath: 房间.webp
                """);

        ImportResult result = importer.importDirectory(dir);
        JsonNode events = events(result.scriptCode());
        ImportReport report = result.report();

        assertEquals("/backgrounds/night.webp", events.get(0).path("imagePath").asText(), "命中映射表应改写");
        assertEquals("房间.webp", events.get(1).path("imagePath").asText(), "未命中不许替换");

        assertEquals(1, report.yellow().size(), "未命中的那条要进 yellow");
        assertEquals("资源不存在于仓库", report.yellow().get(0).issue());
        assertEquals(1, report.yellow().get(0).index());
        assertTrue(report.yellow().get(0).detail().contains("房间.webp"), "detail 里要写明是哪个文件");
        assertTrue(report.green().stream().anyMatch(i -> i.type().equals("background") && i.index() == 0),
                "命中的那条留 green");
    }

    // ---------- 3b. 剧本自带资源：复制 + 优先于全局映射 ----------

    @Test
    void copiesScriptAssetsAndPrefersThemOverGlobalMap() throws Exception {
        Path dir = script("asset-case", """
                name: 自带资源章节
                events:
                  - type: background
                    imagePath: 夜晚.webp
                  - type: background
                    imagePath: 占卜摊2.png
                  - type: background
                    imagePath: 不存在.webp
                """);
        byte[] night = "night-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] booth = "booth-bytes".getBytes(StandardCharsets.UTF_8);
        writeAsset(dir, "夜晚.webp", night);
        writeAsset(dir, "占卜摊2.WEBP", booth);

        ImportResult result = importer.importDirectory(dir);
        String code = result.scriptCode();
        JsonNode events = events(code);

        assertEquals("/backgrounds/scripts/" + code + "/夜晚.webp", events.get(0).path("imagePath").asText(),
                "剧本自带同名图应优先于全局 night.webp");
        assertEquals("/backgrounds/scripts/" + code + "/占卜摊2.WEBP", events.get(1).path("imagePath").asText(),
                "png 引用应命中同名 webp（扩展名不敏感）");

        Path copied = Path.of("target/test-assets/backgrounds/scripts", code, "夜晚.webp");
        assertArrayEquals(night, Files.readAllBytes(copied), "图片要原样复制到按剧本分的目录");

        assertEquals(1, result.report().yellow().size(), "只有真没有的那条进 yellow：" + result.report().yellow());
        assertEquals("资源不存在于仓库", result.report().yellow().get(0).issue());
        assertEquals(2, result.report().green().stream().filter(i -> "background".equals(i.type())).count());
    }

    // ---------- 3c. zip 入口同样扫 Assets/Backgrounds ----------

    @Test
    void readsScriptAssetsFromZip() throws Exception {
        byte[] zip = zipBytes(Map.of(
                "demo/story_config.yaml", """
                        script_name: zip 资源剧本
                        intro_chapter: main
                        """.getBytes(StandardCharsets.UTF_8),
                "demo/Chapters/main.yaml", """
                        name: 主章节
                        events:
                          - type: background
                            imagePath: 超市.webp
                        """.getBytes(StandardCharsets.UTF_8),
                "demo/Assets/Backgrounds/超市.webp", "market-bytes".getBytes(StandardCharsets.UTF_8)));

        ImportResult result = importer.importScript(zip, "zip-asset-demo");
        JsonNode events = events("zip-asset-demo");

        assertEquals("/backgrounds/scripts/zip-asset-demo/超市.webp", events.get(0).path("imagePath").asText());
        assertTrue(result.report().yellow().isEmpty(), "zip 里的自带资源也要命中：" + result.report().yellow());
        assertTrue(Files.isRegularFile(Path.of("target/test-assets/backgrounds/scripts/zip-asset-demo/超市.webp")));
    }

    // ---------- 4. next_chapter 悬空 ----------

    @Test
    void danglingNextChapterIsRed() {
        Path dir = script("dangling-case", """
                name: 断链章节
                events:
                  - type: chapter_end
                    end_type: linear
                    next_chapter: "不存在的章节"
                """);

        ImportReport report = importer.importDirectory(dir).report();

        assertEquals(1, report.red().size(), "悬空引用要进 red");
        assertEquals("章节引用悬空", report.red().get(0).issue());
        assertTrue(report.red().get(0).detail().contains("不存在的章节"));
    }

    // ---------- 5. 未知事件类型 ----------

    @Test
    void unknownEventTypeIsRed() {
        Path dir = script("unknown-type-case", """
                name: 成就章节
                events:
                  - type: achievement
                    id: adventure_butter_night
                  - type: narration
                    text: 正常事件
                """);

        ImportReport report = importer.importDirectory(dir).report();

        assertEquals(1, report.red().size(), "未知类型要进 red");
        assertEquals("事件类型未实现", report.red().get(0).issue());
        assertEquals("achievement", report.red().get(0).type());
        assertEquals(1, report.green().size(), "同章节的正常事件仍算 green");
    }

    // ---------- 6. 重复导入不翻倍 ----------

    @Test
    void reimportKeepsChapterCountStable() {
        Path dir = script("reimport-case", """
                name: 第一章
                events:
                  - type: narration
                    text: 第一次
                """);

        ImportResult first = importer.importDirectory(dir);
        int chaptersAfterFirst = chapterRepository.findByScriptIdOrderByOrderNumAsc(first.scriptId()).size();
        long scriptsAfterFirst = scriptRepository.count();

        ImportResult second = importer.importDirectory(dir);
        int chaptersAfterSecond = chapterRepository.findByScriptIdOrderByOrderNumAsc(second.scriptId()).size();

        assertEquals(first.scriptCode(), second.scriptCode(), "同一来源复用同一个 code");
        assertTrue(first.created());
        assertFalse(second.created(), "第二次是重建，不是新建");
        assertEquals(chaptersAfterFirst, chaptersAfterSecond, "章节数不该翻倍");
        assertEquals(scriptsAfterFirst, scriptRepository.count(), "剧本行也不该多出来");
    }

    // ---------- 附加：zip 入口 ----------

    @Test
    void importsFromZip() throws Exception {
        byte[] zip = zip(Map.of(
                "demo/story_config.yaml", """
                        script_name: zip 剧本
                        intro_chapter: main
                        adventure:
                          bound_character_folder: "诺一钦灵"
                        """,
                "demo/Chapters/main.yaml", """
                        name: 主章节
                        events:
                          - type: narration
                            text: "%player% 来了"
                        """));

        ImportResult result = importer.importScript(zip, "zip-demo");

        assertEquals("zip-demo", result.scriptCode());
        assertEquals(1, result.report().chapters());
        InterviewScript script = scriptRepository.findByCode("zip-demo").orElseThrow();
        assertEquals("zip 剧本", script.getScriptName());
        assertEquals("friendly", interviewerCodeOf(script), "诺一钦灵 → friendly");
        assertTrue(chaptersJson("zip-demo").contains("{user}"), "zip 内容同样做占位符换算");
    }

    // ---------- 工具 ----------

    /** 造一个最小剧本目录：story_config.yaml + Chapters/{name}.yaml。 */
    private Path script(String folder, String chapterYaml) {
        return script(folder, "main", chapterYaml);
    }

    private Path script(String folder, String chapterCode, String chapterYaml) {
        Path root = tempDir.resolve(folder);
        try {
            Files.createDirectories(root.resolve("Chapters"));
            Files.writeString(root.resolve("story_config.yaml"), """
                    script_name: %s
                    intro_chapter: %s
                    description: 测试用剧本
                    recommand_start: 测试
                    adventure:
                      bound_character_folder: "诺一钦灵"
                    """.formatted(folder, chapterCode), StandardCharsets.UTF_8);
            Files.writeString(root.resolve("Chapters").resolve(chapterCode + ".yaml"), chapterYaml,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return root;
    }

    private String chaptersJson(String scriptCode) {
        InterviewScript script = scriptRepository.findByCode(scriptCode).orElseThrow();
        List<InterviewChapter> chapters = chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId());
        return chapters.stream().map(InterviewChapter::getEvents).reduce("", String::concat);
    }

    private JsonNode events(String scriptCode) throws Exception {
        InterviewScript script = scriptRepository.findByCode(scriptCode).orElseThrow();
        InterviewChapter chapter = chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId()).get(0);
        return JSON.readTree(chapter.getEvents());
    }

    private String interviewerCodeOf(InterviewScript script) {
        if (script.getInterviewerId() == null) {
            return null;
        }
        return interviewerRepository.findById(script.getInterviewerId()).map(i -> i.getCode()).orElse(null);
    }

    /** 放一张剧本自带背景：{@code <scriptDir>/Assets/Backgrounds/<fileName>}。 */
    private void writeAsset(Path scriptDir, String fileName, byte[] bytes) {
        Path dir = scriptDir.resolve("Assets").resolve("Backgrounds");
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        Map<String, byte[]> raw = new java.util.LinkedHashMap<>();
        entries.forEach((k, v) -> raw.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(raw);
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}

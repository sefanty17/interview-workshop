package cn.edu.zjut.interview.importer;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.importer.ImportReport.Item;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * LingChat 剧本导入器（规格 B5-BE §2.1）。
 *
 * <p>做三件事，顺序不能换：
 * <ol>
 *   <li><b>解析</b> {@code story_config.yaml} + {@code Chapters/**}{@code /*.yaml}</li>
 *   <li><b>审计</b> 每条事件能不能跑，产出 {@link ImportReport}（绿/黄/红）</li>
 *   <li><b>落库</b>：剧本一行 + 章节若干行，同一剧本重导先删章节再重建（不碰 session）</li>
 * </ol>
 *
 * <p>审计是<b>只读判断</b>：黄项（资源不在映射表、角色没定义、缺 prompt）与红项
 * （未知事件类型、章节悬空）一律只记录，不改写成"看起来能跑"的样子。
 * 唯一会动的字段是规格 §2.2 明确要求等价换算的三类：占位符、粒子名、背景路径。
 */
@Service
public class ScriptYamlImporter {

    private static final Logger log = LoggerFactory.getLogger(ScriptYamlImporter.class);

    private static final String CONFIG_FILE = "story_config.yaml";
    private static final String CHAPTERS_DIR = "chapters";
    private static final String SOURCE_FOLDER_KEY = "source_folder";
    private static final String IMPORTED_KEY = "imported";

    private final InterviewScriptRepository scriptRepository;
    private final InterviewChapterRepository chapterRepository;
    private final InterviewerRepository interviewerRepository;
    private final ObjectMapper objectMapper;

    /**
     * 剧本自带图片的落盘根目录（对应前端 {@code public/backgrounds/scripts}）。
     * 后端进程一般从 {@code backend/} 启动，所以默认值向上一级找 {@code frontend}。
     */
    private final Path assetRoot;

    public ScriptYamlImporter(InterviewScriptRepository scriptRepository,
                              InterviewChapterRepository chapterRepository,
                              InterviewerRepository interviewerRepository,
                              ObjectMapper objectMapper,
                              @Value("${app.import.asset-target:../frontend/public/backgrounds/scripts}")
                              String assetTarget) {
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.interviewerRepository = interviewerRepository;
        this.objectMapper = objectMapper;
        this.assetRoot = Path.of(assetTarget).toAbsolutePath().normalize();
    }

    // ==================== 对外入口 ====================

    /**
     * 导入一个 zip（zip 内可以是剧本目录本身，也可以再套一层目录）。
     *
     * @param zipBytes   zip 内容
     * @param scriptCode 期望的剧本 code；空则自动分配 {@code imported-N} 或沿用同一来源的旧 code
     */
    @Transactional
    public ImportResult importScript(byte[] zipBytes, String scriptCode) {
        Source source = readZip(zipBytes);
        return doImport(source, scriptCode);
    }

    /** 开发用：直接扫本机目录（{@code references/lingchat/data/game_data/scripts/...}）。 */
    @Transactional
    public ImportResult importDirectory(Path dir) {
        Source source = readDirectory(dir);
        return doImport(source, null);
    }

    // ==================== 读取：目录 / zip ====================

    /**
     * 一次导入的原料：来源标识 + story_config 树 + 章节（code → YAML 文本）
     * + 剧本自带背景（原文件名 → 字节）。
     */
    private record Source(String sourceFolder, Map<String, Object> config, Map<String, String> chapters,
                          Map<String, byte[]> assets) {
    }

    private Source readDirectory(Path dir) {
        Path configPath = dir.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configPath)) {
            throw new InvalidScriptException("剧本目录里没有 " + CONFIG_FILE + "：" + dir);
        }
        Map<String, String> chapters = new TreeMap<>();
        Path chaptersRoot = dir.resolve("Chapters");
        try (Stream<Path> walk = Files.walk(chaptersRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> isYaml(p.getFileName().toString()))
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase(CONFIG_FILE))
                    .forEach(p -> {
                        String code = chaptersRoot.relativize(p).toString().replace('\\', '/');
                        chapters.put(stripYaml(code), readText(p));
                    });
        } catch (IOException e) {
            throw new InvalidScriptException("读取章节目录失败：" + chaptersRoot + "（" + e.getMessage() + "）");
        }
        if (chapters.isEmpty()) {
            throw new InvalidScriptException("剧本目录里没有章节 yaml：" + chaptersRoot);
        }
        return new Source(lastSegment(dir), parseYaml(readText(configPath)), chapters, readAssetsDirectory(dir));
    }

    /** 扫 {@code <scriptDir>/Assets/Backgrounds/} 这一层的图片（目录名不区分大小写）。 */
    private static Map<String, byte[]> readAssetsDirectory(Path scriptDir) {
        Path backgrounds = childNamed(childNamed(scriptDir, "Assets"), "Backgrounds");
        if (backgrounds == null) {
            return Map.of();
        }
        Map<String, byte[]> assets = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(backgrounds)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> ResourceMapping.isImage(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            assets.put(p.getFileName().toString(), Files.readAllBytes(p));
                        } catch (IOException e) {
                            log.warn("[ScriptYamlImporter] 读取剧本自带资源失败 {}：{}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[ScriptYamlImporter] 读取资源目录失败 {}：{}", backgrounds, e.getMessage());
        }
        return assets;
    }

    /** 精确名优先，找不到再按忽略大小写匹配这一层的子目录。 */
    private static Path childNamed(Path parent, String name) {
        if (parent == null) {
            return null;
        }
        Path exact = parent.resolve(name);
        if (Files.isDirectory(exact)) {
            return exact;
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Source readZip(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = new ByteArrayInputStream(zipBytes);
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName().replace('\\', '/'), zip.readAllBytes());
            }
        } catch (IOException e) {
            throw new InvalidScriptException("不是合法的 zip：" + e.getMessage());
        }

        // story_config.yaml 取层级最浅的那一份（zip 里可能附带 __MACOSX 之类的干扰项）
        String configEntry = entries.keySet().stream()
                .filter(name -> name.endsWith("/" + CONFIG_FILE) || name.equals(CONFIG_FILE))
                .min((a, b) -> Integer.compare(depth(a), depth(b)))
                .orElseThrow(() -> new InvalidScriptException("zip 里没有找到 " + CONFIG_FILE));

        String prefix = configEntry.equals(CONFIG_FILE) ? "" : configEntry.substring(0, configEntry.lastIndexOf('/') + 1);
        String chaptersPrefix = prefix + "Chapters/";

        Map<String, String> chapters = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(chaptersPrefix) || !isYaml(name)) {
                continue;
            }
            if (name.substring(name.lastIndexOf('/') + 1).equalsIgnoreCase(CONFIG_FILE)) {
                continue;
            }
            String code = name.substring(chaptersPrefix.length());
            chapters.put(stripYaml(code), new String(e.getValue(), StandardCharsets.UTF_8));
        }
        if (chapters.isEmpty()) {
            throw new InvalidScriptException("zip 里没有 " + chaptersPrefix + "**/*.yaml");
        }
        String folder = prefix.isEmpty() ? "" : lastSegment(Path.of(prefix.replaceAll("/$", "")));
        return new Source(folder, parseYaml(new String(entries.get(configEntry), StandardCharsets.UTF_8)), chapters,
                readZipAssets(entries, prefix));
    }

    /** zip 里的 {@code Assets/Backgrounds/} 只收这一层的图片，忽略更深层的子目录。 */
    private static Map<String, byte[]> readZipAssets(Map<String, byte[]> entries, String prefix) {
        String assetsPrefix = prefix + "Assets/Backgrounds/";
        Map<String, byte[]> assets = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.length() <= assetsPrefix.length()
                    || !name.regionMatches(true, 0, assetsPrefix, 0, assetsPrefix.length())) {
                continue;
            }
            String fileName = name.substring(assetsPrefix.length());
            if (fileName.contains("/") || !ResourceMapping.isImage(fileName)) {
                continue;
            }
            assets.put(fileName, e.getValue());
        }
        return assets;
    }

    private static int depth(String name) {
        return (int) name.chars().filter(c -> c == '/').count();
    }

    private static String lastSegment(Path dir) {
        Path name = dir.getFileName();
        return name == null ? dir.toString() : name.toString();
    }

    private static boolean isYaml(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static String stripYaml(String relative) {
        return relative.replaceAll("(?i)\\.ya?ml$", "");
    }

    private static String readText(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidScriptException("读取失败：" + p + "（" + e.getMessage() + "）");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String text) {
        Object loaded = newYaml().load(text);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new InvalidScriptException("YAML 顶层不是映射（key: value）");
        }
        return (Map<String, Object>) map;
    }

    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    // ==================== 审计 + 落库 ====================

    private ImportResult doImport(Source source, String explicitCode) {
        Map<String, Object> config = source.config();
        String scriptName = str(config.get("script_name"), source.sourceFolder().isEmpty() ? "导入的剧本" : source.sourceFolder());
        String introChapter = stripYaml(str(config.get("intro_chapter"), ""));
        String characterFolder = readAdventureCharacterFolder(config);

        String code = resolveCode(explicitCode, source.sourceFolder());
        Map<String, String> scriptAssets = publishAssets(code, source.assets());
        List<Item> green = new ArrayList<>();
        List<Item> yellow = new ArrayList<>();
        List<Item> red = new ArrayList<>();

        List<String> chapterCodes = new ArrayList<>(source.chapters().keySet());
        Map<String, JsonNode> normalized = new LinkedHashMap<>();
        int eventTotal = 0;

        for (String chapterCode : chapterCodes) {
            ObjectNode chapter = toChapterNode(chapterCode, source.chapters().get(chapterCode));
            JsonNode events = chapter.get("events");
            ArrayNode rewritten = objectMapper.createArrayNode();
            if (events != null && events.isArray()) {
                for (int i = 0; i < events.size(); i++) {
                    JsonNode raw = events.get(i);
                    if (!raw.isObject()) {
                        red.add(ImportReport.Item.red(chapterCode, i, "unknown",
                                "事件不是对象", "该元素是 " + raw.getNodeType() + "，引擎只认 {type: ...} 的对象"));
                        rewritten.add(raw);
                        continue;
                    }
                    ObjectNode event = ((ObjectNode) raw).deepCopy();
                    replacePlaceholders(event);
                    auditEvent(chapterCode, i, event, chapterCodes, scriptAssets, green, yellow, red);
                    rewritten.add(event);
                }
                eventTotal += events.size();
            }
            chapter.set("events", rewritten);
            normalized.put(chapterCode, chapter);
        }

        // 剧本级：intro_chapter 必须指向真实存在的章节
        if (!introChapter.isEmpty() && !chapterCodes.contains(introChapter)) {
            red.add(ImportReport.Item.red("", -1, "intro_chapter", "intro_chapter 悬空",
                    "intro_chapter=" + introChapter + " 不在章节列表 [" + String.join(", ", chapterCodes) + "] 里"));
        }
        if (introChapter.isEmpty()) {
            yellow.add(ImportReport.Item.yellow("", -1, "intro_chapter", "缺少 intro_chapter",
                    "story_config.yaml 没写 intro_chapter，开场章节需要手动指定"));
        }

        Persisted persisted = persist(code, scriptName, introChapter, config, characterFolder,
                source.sourceFolder(), normalized);
        InterviewScript script = persisted.script();

        ImportReport report = new ImportReport(code, scriptName, chapterCodes.size(), eventTotal,
                green, yellow, red, new ImportReport.Summary(green.size(), yellow.size(), red.size()));
        log.info("[ScriptYamlImporter] {} 导入完成：{} 章 / {} 事件 / 绿 {} 黄 {} 红 {}",
                code, report.chapters(), report.events(),
                report.summary().green(), report.summary().yellow(), report.summary().red());
        return new ImportResult(code, scriptName, persisted.created(), script.getId(), report);
    }

    /** 落库结果：created 用于告诉调用方这次是新建还是就地重建。 */
    private record Persisted(InterviewScript script, boolean created) {
    }

    private Persisted persist(String code, String scriptName, String introChapter, Map<String, Object> config,
                              String characterFolder, String sourceFolder, Map<String, JsonNode> chapters) {
        Optional<InterviewScript> existing = scriptRepository.findByCode(code);
        boolean created = existing.isEmpty();
        InterviewScript script = existing.orElseGet(InterviewScript::new);

        script.setCode(code);
        script.setScriptName(scriptName);
        script.setIntroChapter(introChapter.isEmpty() ? chapters.keySet().iterator().next() : introChapter);
        script.setDescription(str(config.get("description"), null));
        script.setRecommandStart(str(config.get("recommand_start"), null));
        script.setTriggerMode(readTriggerMode(config));
        script.setJobCode("java");
        script.setOrderNum(readOrder(config));
        script.setScriptSettings(buildSettings(config, sourceFolder));
        script.setCharacters("[\"" + ResourceMapping.MAIN_CHARACTER + "\"]");
        script.setInterviewerId(interviewerIdOf(characterFolder));
        script.setEnabled(Boolean.TRUE);
        if (script.getCreatedAt() == null) {
            script.setCreatedAt(java.time.LocalDateTime.now());
        }
        InterviewScript saved = scriptRepository.saveAndFlush(script);

        // 幂等：先清掉这个剧本的旧章节再重建。会话与消息不在这里，不受影响。
        chapterRepository.deleteByScriptId(saved.getId());
        chapterRepository.flush();

        int order = 1;
        List<InterviewChapter> rows = new ArrayList<>(chapters.size());
        for (Map.Entry<String, JsonNode> e : chapters.entrySet()) {
            InterviewChapter row = new InterviewChapter();
            row.setScriptId(saved.getId());
            row.setCode(e.getKey());
            JsonNode node = e.getValue();
            String name = node.path("name").asText("");
            row.setName(name.isEmpty() ? e.getKey() : name);
            row.setOrderNum(order++);
            row.setEvents(node.path("events").toString());
            rows.add(row);
        }
        chapterRepository.saveAll(rows);
        chapterRepository.flush();
        return new Persisted(saved, created);
    }

    // ==================== 剧本自带资源 ====================

    /**
     * 把剧本自带的背景复制到 {@code <assetRoot>/<scriptCode>/}，返回「原文件名 → 前端 URL」。
     *
     * <p>按剧本分目录，避免不同剧本同名文件互相覆盖；每次导入先清空该剧本目录，重复导入不会留旧图。
     * 复制失败时返回空表：这些背景会按「资源不存在于仓库」进 yellow，绝不假装成功。
     */
    private Map<String, String> publishAssets(String code, Map<String, byte[]> assets) {
        if (assets.isEmpty()) {
            return Map.of();
        }
        Path dir = assetRoot.resolve(code);
        try {
            deleteRecursively(dir);
            Files.createDirectories(dir);
            Map<String, String> urls = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : assets.entrySet()) {
                String fileName = Path.of(e.getKey()).getFileName().toString();
                Files.write(dir.resolve(fileName), e.getValue());
                urls.put(fileName, ResourceMapping.SCRIPT_ASSET_PREFIX + code + "/" + fileName);
            }
            log.info("[ScriptYamlImporter] 剧本 {} 自带资源 {} 张 → {}", code, urls.size(), dir);
            return urls;
        } catch (IOException e) {
            log.warn("[ScriptYamlImporter] 剧本 {} 自带资源复制失败（按缺失处理）：{}", code, e.getMessage());
            return Map.of();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ==================== 单事件审计 ====================

    private void auditEvent(String chapterCode, int index, ObjectNode event, List<String> chapterCodes,
                            Map<String, String> scriptAssets,
                            List<Item> green, List<Item> yellow, List<Item> red) {
        String type = event.path("type").asText("");
        List<String[]> yellowIssues = new ArrayList<>();
        List<String[]> redIssues = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        if (type.isEmpty()) {
            redIssues.add(new String[]{"事件缺少 type", "该事件没有 type 字段，引擎无法分发"});
        } else if (!ResourceMapping.EVENT_TYPES.contains(type)) {
            redIssues.add(new String[]{"事件类型未实现",
                    type + " 不在本项目的 16 种事件里（引擎会直接抛未知事件），导入后需要改写或删除"});
        }

        switch (type) {
            case "background" -> auditBackground(event, scriptAssets, yellowIssues, notes);
            case "background_effect" -> auditEffect(event, yellowIssues, notes);
            default -> {
            }
        }

        auditCharacter(event, type, yellowIssues);
        auditRequiredFields(event, type, yellowIssues);
        auditChapterEnd(event, type, chapterCodes, yellowIssues, redIssues);

        if (!redIssues.isEmpty()) {
            red.add(ImportReport.Item.red(chapterCode, index, type.isEmpty() ? "unknown" : type,
                    redIssues.get(0)[0], redIssues.get(0)[1]));
        } else if (!yellowIssues.isEmpty()) {
            yellow.add(ImportReport.Item.yellow(chapterCode, index, type,
                    yellowIssues.get(0)[0], yellowIssues.get(0)[1]));
        } else {
            green.add(ImportReport.Item.green(chapterCode, index, type,
                    notes.isEmpty() ? "可执行" : "可执行（" + String.join("；", notes) + "）"));
        }
    }

    /**
     * 背景路径：剧本自带资源优先，其次全局映射表；两处都没有才进 yellow。
     * 命中即改写（规格 §2.4），未命中保留原值，不编路径。
     */
    private void auditBackground(ObjectNode event, Map<String, String> scriptAssets,
                                 List<String[]> yellow, List<String> notes) {
        JsonNode node = event.get("imagePath");
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            yellow.add(new String[]{"缺少 imagePath", "background 事件没有 imagePath，切不了背景"});
            return;
        }
        String raw = node.asText();
        Optional<ResourceMapping.Background> resolved = ResourceMapping.resolveBackground(raw, scriptAssets);
        if (resolved.isPresent()) {
            ResourceMapping.Background hit = resolved.get();
            event.put("imagePath", hit.url());
            String label = "script".equals(hit.origin())
                    ? "剧本自带 " + raw + " → " + hit.url()
                    : "全局映射 " + raw + " → " + hit.url();
            notes.add("背景 " + label);
        } else {
            yellow.add(new String[]{"资源不存在于仓库",
                    "imagePath=" + raw + " 既不在剧本 Assets/Backgrounds 里，也不在全局映射表里，播放时会空白（未替换）"});
        }
    }

    /** 粒子名：大小写/中文别名归一到前端 key；清空值归一成 none；其余进 yellow。 */
    private void auditEffect(ObjectNode event, List<String[]> yellow, List<String> notes) {
        JsonNode node = event.get("effect");
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            notes.add("无特效");
            event.put("effect", "none");
            return;
        }
        String raw = node.asText();
        Optional<String> mapped = ResourceMapping.mapEffect(raw);
        if (mapped.isPresent()) {
            event.put("effect", mapped.get());
            if (!raw.equals(mapped.get())) {
                notes.add("特效 " + raw + " → " + mapped.get());
            }
        } else {
            yellow.add(new String[]{"特效未知", "effect=" + raw + " 不在粒子表里，前端会清空当前特效（未替换）"});
        }
    }

    /** 角色引用：不是 MAIN 又不在 characters 里 → yellow。 */
    private void auditCharacter(ObjectNode event, String type, List<String[]> yellow) {
        JsonNode node = event.get("character");
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return;
        }
        String character = node.asText();
        if (ResourceMapping.MAIN_CHARACTER.equals(character)) {
            return;
        }
        yellow.add(new String[]{"角色未定义",
                "character=" + character + " 不在本剧本 characters=[" + ResourceMapping.MAIN_CHARACTER + "] 里"});
    }

    /** 必需字段：ai_dialogue 有 character 却没 prompt，只能让模型自由发挥。 */
    private void auditRequiredFields(ObjectNode event, String type, List<String[]> yellow) {
        if (!"ai_dialogue".equals(type)) {
            return;
        }
        boolean hasCharacter = event.hasNonNull("character");
        boolean hasPrompt = event.hasNonNull("prompt") && !event.get("prompt").asText().isBlank();
        if (hasCharacter && !hasPrompt) {
            yellow.add(new String[]{"缺少 prompt",
                    "ai_dialogue 有 character=" + event.get("character").asText() + " 却没有 prompt，本段没有剧情提示只靠上下文"});
        }
        if (!hasCharacter) {
            yellow.add(new String[]{"缺少 character", "ai_dialogue 没写 character，无法定位说话角色"});
        }
    }

    /** 章节链：linear 必须有 next_chapter；指向不存在的章节（且不是 end）→ red。 */
    private void auditChapterEnd(ObjectNode event, String type, List<String> chapterCodes,
                                 List<String[]> yellow, List<String[]> red) {
        if (!"chapter_end".equals(type)) {
            return;
        }
        String endType = event.path("end_type").asText("linear");
        List<String> targets = new ArrayList<>();
        if (event.hasNonNull("next_chapter")) {
            targets.add(event.get("next_chapter").asText());
        }
        if (event.path("options").isArray()) {
            for (JsonNode option : event.get("options")) {
                if (option.hasNonNull("next")) {
                    targets.add(option.get("next").asText());
                }
            }
        }
        if (targets.isEmpty()) {
            if ("linear".equals(endType)) {
                yellow.add(new String[]{"缺少 next_chapter", "chapter_end 是 linear 但没有 next_chapter，走到这里会停住"});
            }
            return;
        }
        for (String target : targets) {
            if (target.isBlank() || ResourceMapping.END_SENTINEL.equals(target)) {
                continue;
            }
            if (!chapterCodes.contains(target)) {
                red.add(new String[]{"章节引用悬空",
                        "next_chapter=" + target + " 不在章节列表 [" + String.join(", ", chapterCodes) + "] 里，走到这里会断"});
            }
        }
    }

    // ==================== 字段换算 ====================

    /** {@code %player% → {user}}、{@code %char% → {char}}：递归替换所有文本节点（规格 §2.2）。 */
    private static void replacePlaceholders(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (child.isTextual()) {
                    String replaced = child.asText()
                            .replace("%player%", "{user}")
                            .replace("%char%", "{char}");
                    if (!replaced.equals(child.asText())) {
                        object.put(name, replaced);
                    }
                } else {
                    replacePlaceholders(child);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                replacePlaceholders(child);
            }
        }
    }

    private ObjectNode toChapterNode(String code, String yamlText) {
        Map<String, Object> map;
        try {
            map = parseYaml(yamlText);
        } catch (RuntimeException e) {
            throw new InvalidScriptException("章节 " + code + " 解析失败：" + e.getMessage());
        }
        JsonNode node = objectMapper.valueToTree(map);
        if (!(node instanceof ObjectNode object)) {
            throw new InvalidScriptException("章节 " + code + " 的 YAML 顶层不是映射");
        }
        if (!object.has("events") || !object.get("events").isArray()) {
            object.putArray("events");
        }
        return object;
    }

    // ==================== story_config 字段 ====================

    @SuppressWarnings("unchecked")
    private static String readAdventureCharacterFolder(Map<String, Object> config) {
        Object adventure = config.get("adventure");
        if (adventure instanceof Map<?, ?> map) {
            Object folder = ((Map<String, Object>) map).get("bound_character_folder");
            if (folder != null) {
                return String.valueOf(folder);
            }
        }
        return null;
    }

    private static String readTriggerMode(Map<String, Object> config) {
        Object adventure = config.get("adventure");
        if (adventure instanceof Map<?, ?> map) {
            Object trigger = ((Map<?, ?>) map).get("trigger");
            if (trigger instanceof Map<?, ?> triggerMap) {
                Object mode = ((Map<?, ?>) triggerMap).get("mode");
                if (mode != null && !String.valueOf(mode).isBlank()) {
                    return String.valueOf(mode);
                }
            }
        }
        return "manual";
    }

    private static Integer readOrder(Map<String, Object> config) {
        Object adventure = config.get("adventure");
        if (adventure instanceof Map<?, ?> map) {
            Object order = ((Map<?, ?>) map).get("order");
            if (order instanceof Number number) {
                return number.intValue();
            }
        }
        return 0;
    }

    /** script_settings 保留原值，另记导入来源（source_folder 是重复导入时复用 code 的依据）。 */
    private String buildSettings(Map<String, Object> config, String sourceFolder) {
        Map<String, Object> settings = new LinkedHashMap<>();
        Object raw = config.get("script_settings");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> settings.put(String.valueOf(k), v));
        }
        settings.put(IMPORTED_KEY, true);
        if (sourceFolder != null && !sourceFolder.isBlank()) {
            settings.put(SOURCE_FOLDER_KEY, sourceFolder);
        }
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            return "{\"imported\":true}";
        }
    }

    /** 角色文件夹 → interviewer_id；映射到的那张卡不存在时回落 strict，再不行给 null（不让外键挡住导入）。 */
    private Long interviewerIdOf(String characterFolder) {
        String code = ResourceMapping.interviewerCodeFor(characterFolder);
        Optional<Interviewer> interviewer = interviewerRepository.findByCode(code)
                .or(() -> interviewerRepository.findByCode(ResourceMapping.DEFAULT_INTERVIEWER));
        return interviewer.map(Interviewer::getId).orElse(null);
    }

    // ==================== code 分配 ====================

    /**
     * code 必须是 ASCII（规格 §2.1），所以中文目录名不能直接当 code：
     * 1. 调用方显式给了就用；
     * 2. 同一来源（script_settings.source_folder）已导过 → 沿用旧 code（重复导入不产生新剧本）；
     * 3. 否则分配最小的 {@code imported-N}。
     */
    private String resolveCode(String explicitCode, String sourceFolder) {
        if (explicitCode != null && !explicitCode.isBlank()) {
            return explicitCode.trim();
        }
        if (sourceFolder != null && !sourceFolder.isBlank()) {
            for (InterviewScript script : scriptRepository.findAll()) {
                if (sourceFolder.equals(sourceFolderOf(script))) {
                    return script.getCode();
                }
            }
        }
        for (int n = 1; n < 10_000; n++) {
            String candidate = "imported-" + n;
            if (scriptRepository.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new InvalidScriptException("imported-N 已用尽，请显式指定 scriptCode");
    }

    private String sourceFolderOf(InterviewScript script) {
        String raw = script.getScriptSettings();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node.hasNonNull(SOURCE_FOLDER_KEY) ? node.get(SOURCE_FOLDER_KEY).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? fallback : s;
    }

    /** 剧本本身不合法（缺文件、YAML 坏了、zip 打不开），由 API 层转 400。 */
    public static class InvalidScriptException extends RuntimeException {
        public InvalidScriptException(String message) {
            super(message);
        }
    }
}

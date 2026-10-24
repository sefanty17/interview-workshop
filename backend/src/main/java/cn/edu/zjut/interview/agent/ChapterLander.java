package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.editor.Diagnostic;
import cn.edu.zjut.interview.editor.ScriptValidator;
import cn.edu.zjut.interview.editor.Severity;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 落盘桥接：把剧本编辑器 AI 写出的章节 YAML 落进 {@code interview_chapter} 表，并当场自检。
 *
 * <p><b>为什么需要这一层（技术栈差异，不是功能裁剪）</b>
 * 原版 {@code Chapters/<id>.yaml} 既是编辑器 AI 的产物、也是引擎与校验器的输入，一份文件通吃。
 * 本项目章节存在 {@code interview_chapter.events}（CLOB 里的 JSON 数组），
 * 于是「写文件」与「进库」分成了两步：
 * <ul>
 *   <li>{@link SkillAgentFileTools#writeFile} 负责把 YAML 写进沙箱（AI 看到的产物）；</li>
 *   <li>本类负责把同一份 YAML 送进库里（引擎/校验器真正读的那份），并回一段
 *       {@code [章节自检]} 给 AI。</li>
 * </ul>
 * 少了这层，AI 落盘完的章节既进不了库、也校验不到、引擎更跑不到 —— 四角色链路是断的。
 *
 * <p>字段映射与原版章节 YAML 一致：
 * <ul>
 *   <li>{@code name:} → {@link InterviewChapter#getName()}</li>
 *   <li>{@code events:} → {@link InterviewChapter#getEvents()}（YAML 数组 → JSON 数组 CLOB）</li>
 *   <li>其余顶层键 → 无对应列，丢弃（原版存进 {@code extra}，本项目表结构里没有）</li>
 * </ul>
 */
@Component
public class ChapterLander {

    private static final Logger log = LoggerFactory.getLogger(ChapterLander.class);

    /** 单次自检回执里最多列出的错误条数（对齐 {@code tools.rs::MAX_ERROR}）。 */
    private static final int MAX_CHECK_ERRORS = 20;

    /** 单次自检回执里最多列出的警告条数。 */
    private static final int MAX_CHECK_WARNS = 10;

    private final InterviewScriptRepository scriptRepository;
    private final InterviewChapterRepository chapterRepository;
    private final ScriptValidator validator;
    private final ObjectMapper objectMapper;

    public ChapterLander(InterviewScriptRepository scriptRepository,
                         InterviewChapterRepository chapterRepository,
                         ScriptValidator validator,
                         ObjectMapper objectMapper) {
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /** 落盘结果：{@code ok=false} 时 {@code message} 说明为什么没落成（原文给 AI 看）。 */
    public record Landing(boolean ok, String message, String checkReceipt) {

        static Landing failed(String message) {
            return new Landing(false, message, null);
        }

        static Landing skipped(String message) {
            return new Landing(true, message, null);
        }

        static Landing done(String message, String receipt) {
            return new Landing(true, message, receipt);
        }
    }

    /**
     * 从写入路径里认出「哪一章」：{@code .../scripts/<key>/Chapters/<id>.yaml}。
     *
     * <p>认不出就返回 {@code null}（那不是章节文件，本类不管）。大小写不敏感，
     * 与原版 {@code chapter_id_of_path} 一致。
     */
    static String chapterIdOfPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/').toLowerCase();
        int at = normalized.indexOf("/chapters/");
        if (at < 0) {
            return null;
        }
        String tail = normalized.substring(at + "/chapters/".length());
        if (tail.endsWith(".yaml")) {
            tail = tail.substring(0, tail.length() - ".yaml".length());
        } else if (tail.endsWith(".yml")) {
            tail = tail.substring(0, tail.length() - ".yml".length());
        } else {
            return null;
        }
        return tail.isBlank() ? null : tail;
    }

    /**
     * 把一份章节 YAML 落进库里。
     *
     * @param scriptKey 会话绑定的剧本 code；为空表示还没绑定，不落盘（先让用户确认剧本）
     * @param path      写入路径（用来反推章节 id 并判断是不是章节文件）
     * @param content   YAML 全文
     */
    public Landing land(String scriptKey, String path, String content) {
        String chapterId = chapterIdOfPath(path);
        if (chapterId == null) {
            return Landing.skipped(null);
        }
        if (scriptKey == null || scriptKey.isBlank()) {
            return Landing.failed("这一章写进文件了，但当前会话还没绑定剧本，所以没能进剧本库。"
                    + "先确认要写进哪个剧本（新建或指定一个），再重新 write_file 一次。");
        }
        InterviewScript script = scriptRepository.findByCode(scriptKey).orElse(null);
        if (script == null) {
            return Landing.failed("剧本「" + scriptKey + "」在剧本库里不存在，这一章进不了库。"
                    + "先让用户确认剧本，或先写 story_config.yaml 把剧本建出来。");
        }

        ObjectNode chapterNode;
        try {
            chapterNode = parseChapterYaml(chapterId, content);
        } catch (IllegalArgumentException e) {
            return Landing.failed("`" + chapterId + "` 不是可用的章节 YAML：" + e.getMessage()
                    + "。文件已写到盘上，但库里没进这一章。");
        }

        String name = chapterNode.path("name").asText(chapterId);
        String eventsJson = chapterNode.path("events").toString();

        InterviewChapter chapter = chapterRepository
                .findByScriptIdAndCode(script.getId(), chapterId)
                .orElseGet(() -> {
                    InterviewChapter created = new InterviewChapter();
                    created.setScriptId(script.getId());
                    created.setCode(chapterId);
                    created.setOrderNum(chapterRepository
                            .findByScriptIdOrderByOrderNumAsc(script.getId()).size() + 1);
                    return created;
                });
        // 覆盖前留最近一份（等价原版 .bak）
        chapter.setEventsBackup(chapter.getEvents());
        if (name != null && !name.isBlank()) {
            chapter.setName(name);
        }
        chapter.setEvents(eventsJson);
        chapterRepository.saveAndFlush(chapter);
        log.info("[ChapterLander] 剧本 {} 落盘章节 {}（{} 个事件）",
                scriptKey, chapterId, chapterNode.path("events").size());

        return Landing.done("已写进剧本库：剧本 `" + scriptKey + "` · 章节 `" + chapterId
                + "`（" + chapterNode.path("events").size() + " 个事件）",
                selfCheck(scriptKey, chapterId));
    }

    /**
     * 解析章节 YAML → {@code {name, events}}。
     *
     * <p>只认这两个键，其余丢弃；{@code events} 必须能转成 JSON 数组，否则引擎读不了。
     */
    ObjectNode parseChapterYaml(String chapterId, String content) {
        Object loaded = newYaml().load(content == null ? "" : content);
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("顶层不是映射（应当是 name: / events: 这样的键值）");
        }
        ObjectNode out = objectMapper.createObjectNode();
        Object name = raw.get("name");
        if (name != null) {
            out.put("name", String.valueOf(name).trim());
        }
        Object events = raw.get("events");
        ArrayNode array = out.putArray("events");
        if (events instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("events 里有不是对象（{type: ...}）的元素");
                }
                JsonNode node = objectMapper.valueToTree(item);
                if (!node.isObject() || !node.hasNonNull("type") || node.get("type").asText().isBlank()) {
                    throw new IllegalArgumentException("events 里有元素缺少 type");
                }
                array.add(node);
            }
        } else if (events != null) {
            throw new IllegalArgumentException("events 不是数组");
        }
        return out;
    }

    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /**
     * 章节自检：只查这一章（整剧本校验会报一堆「尚未写完」的假错，误导模型去补后续章节）。
     *
     * <p>回执口径对齐 {@code evidence.rs::ChapterCheck::render}：
     * 错误段说「这一章还不算写完，先改这些」，警告段说「记下来即可，不阻断」。
     */
    private String selfCheck(String scriptKey, String chapterId) {
        InterviewScript script = scriptRepository.findByCode(scriptKey).orElse(null);
        if (script == null) {
            return null;
        }
        var report = validator.validate(script,
                chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId()));
        List<String> errors = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        for (Diagnostic d : report.diagnostics()) {
            // 只认这一章的问题；剧本级（章节为 null）与其它章的诊断不在这里报
            if (d.chapter() == null || !chapterId.equals(d.chapter())) {
                continue;
            }
            String line = (d.eventIndex() == null ? "" : "第 " + (d.eventIndex() + 1) + " 个事件 · ")
                    + d.message();
            if (d.severity() == Severity.ERROR) {
                errors.add(line);
            } else if (d.severity() == Severity.WARN) {
                warns.add(line);
            }
        }
        if (errors.isEmpty() && warns.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        if (!errors.isEmpty()) {
            out.append("\n\n[章节自检] 这一章还不算写完，先改这些：\n");
            for (int i = 0; i < Math.min(errors.size(), MAX_CHECK_ERRORS); i++) {
                out.append("- ").append(errors.get(i)).append('\n');
            }
            if (errors.size() > MAX_CHECK_ERRORS) {
                out.append("…另有 ").append(errors.size() - MAX_CHECK_ERRORS).append(" 条错误未显示\n");
            }
            out.append("改好后重新 write_file 覆盖它（不要 append）。\n");
        }
        if (!warns.isEmpty()) {
            out.append("\n[章节自检] 记下来即可，不阻断：\n");
            for (int i = 0; i < Math.min(warns.size(), MAX_CHECK_WARNS); i++) {
                out.append("- ").append(warns.get(i)).append('\n');
            }
            if (warns.size() > MAX_CHECK_WARNS) {
                out.append("…另有 ").append(warns.size() - MAX_CHECK_WARNS).append(" 条警告未显示\n");
            }
        }
        return out.toString();
    }

    /**
     * 章节文件被删 → 库里那一章也删。
     *
     * <p>原版章节即文件，文件没了自然没这一章；本项目章节在库里，得显式跟着删。
     * 不做引用检查（原版也不做），断链留给校验器报 {@code graph.missing_target}。
     *
     * @return 删掉了给一句可读的说明；本来就没有返回 {@link Optional#empty()}
     */
    public Optional<String> deleteChapter(String scriptKey, String chapterId) {
        if (scriptKey == null || scriptKey.isBlank() || chapterId == null || chapterId.isBlank()) {
            return Optional.empty();
        }
        InterviewScript script = scriptRepository.findByCode(scriptKey).orElse(null);
        if (script == null) {
            return Optional.empty();
        }
        var found = chapterRepository.findByScriptIdAndCode(script.getId(), chapterId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        chapterRepository.delete(found.get());
        log.info("[ChapterLander] 剧本 {} 删除章节 {}", scriptKey, chapterId);
        return Optional.of("已从剧本库删掉章节 `" + chapterId + "`（剧本 `" + scriptKey + "`）");
    }

    /**
     * 「已落盘」的章节 id。
     *
     * <p><b>按原版口径 = {@code Chapters/} 下真实存在的文件</b>（{@code stage::derive} 扫的就是目录），
     * 不是库里所有行：本项目的种子章节（{@code intro} / {@code basic} / …）是预置演示数据，
     * 盘上并没有对应 YAML，把它们算进"已落盘"，路由与自检就会看到一堆实际上没有的章节
     * （实测：模型因此认定 {@code 01} 已存在，一直去 list_files 找、一次都不写）。盘上的 YAML 与库里那一行由 {@link #land} 一起维护，两者同步。
     */
    public List<String> writtenChapterIds(String scriptKey) {
        return writtenChapterIds(scriptKey, null);
    }

    /**
     * 同上，但可以在给了剧本包目录时直接扫盘（对齐原版 {@code stage::derive} 看的就是目录）。
     *
     * @param scriptDir 剧本包目录；{@code null} 时退回"库里所有章节"（无目录可用时的保守取法）
     */
    public List<String> writtenChapterIds(String scriptKey, Path scriptDir) {
        if (scriptDir != null) {
            Path chaptersDir = scriptDir.resolve("Chapters");
            if (!Files.isDirectory(chaptersDir)) {
                return List.of();
            }
            try (Stream<Path> walk = Files.walk(chaptersDir, 2)) {
                return walk.filter(Files::isRegularFile)
                        .map(p -> chaptersDir.relativize(p).toString().replace('\\', '/'))
                        .filter(name -> name.endsWith(".yaml") || name.endsWith(".yml"))
                        .map(name -> name.substring(0, name.lastIndexOf('.')))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                log.warn("[ChapterLander] 扫章节目录失败 {}：{}", chaptersDir, e.getMessage());
                return List.of();
            }
        }
        if (scriptKey == null || scriptKey.isBlank()) {
            return List.of();
        }
        InterviewScript script = scriptRepository.findByCode(scriptKey).orElse(null);
        if (script == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (InterviewChapter chapter : chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId())) {
            ids.add(chapter.getCode());
        }
        return ids;
    }

    /** 章节 code → name（自检与诊断里展示用）。 */
    public Map<String, String> chapterNames(String scriptKey) {
        Map<String, String> names = new LinkedHashMap<>();
        if (scriptKey == null || scriptKey.isBlank()) {
            return names;
        }
        InterviewScript script = scriptRepository.findByCode(scriptKey).orElse(null);
        if (script == null) {
            return names;
        }
        for (InterviewChapter chapter : chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId())) {
            names.put(chapter.getCode(), chapter.getName());
        }
        return names;
    }
}

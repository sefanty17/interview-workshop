package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.InterviewChapter;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.domain.InterviewSession;
import cn.edu.zjut.interview.domain.Interviewer;
import cn.edu.zjut.interview.dto.EditorAssetFileView;
import cn.edu.zjut.interview.dto.EditorAssetIndex;
import cn.edu.zjut.interview.dto.EditorChapterContent;
import cn.edu.zjut.interview.dto.EditorChapterSummary;
import cn.edu.zjut.interview.dto.EditorCreateChapterRequest;
import cn.edu.zjut.interview.dto.EditorCreateScriptRequest;
import cn.edu.zjut.interview.dto.EditorScriptDetail;
import cn.edu.zjut.interview.dto.EditorScriptPackage;
import cn.edu.zjut.interview.dto.EditorWriteChapterRequest;
import cn.edu.zjut.interview.dto.EditorWriteConfigRequest;
import cn.edu.zjut.interview.dto.ValidationReportView;
import cn.edu.zjut.interview.editor.EditorBadRequestException;
import cn.edu.zjut.interview.editor.EditorNotFoundException;
import cn.edu.zjut.interview.editor.ScriptValidator;
import cn.edu.zjut.interview.importer.ResourceMapping;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.InterviewSessionRepository;
import cn.edu.zjut.interview.repository.InterviewerRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 剧本编辑器后端（规格 E1）。
 *
 * <p><b>存储映射（技术栈差异，对照原版）</b>
 * <table border="1">
 *   <tr><th>原版</th><th>本项目</th></tr>
 *   <tr><td>{@code story_config.yaml}</td><td>{@code interview_script} 一行</td></tr>
 *   <tr><td>{@code Chapters/<id>.yaml}</td><td>{@code interview_chapter} 一行（{@code code} = 章节 id）</td></tr>
 *   <tr><td>章节 YAML 的 {@code name:}</td><td>{@code interview_chapter.name}</td></tr>
 *   <tr><td>章节 YAML 的 {@code events:}</td><td>{@code interview_chapter.events}（JSON 数组 CLOB）</td></tr>
 *   <tr><td>章节 YAML 的其余顶层键（{@code extra}）</td><td>无对应列，本批丢弃</td></tr>
 *   <tr><td>覆盖前留 {@code <原名>.bak}</td><td>写入前把旧 {@code events} 存进 {@code events_backup}</td></tr>
 *   <tr><td>写临时文件 → {@code rename}</td><td>{@code @Transactional} 单条 UPDATE</td></tr>
 * </table>
 *
 * <p>{@code key} 一律等于 {@code interview_script.code}，{@code chapterId} 一律等于
 * {@code interview_chapter.code}。
 */
@Service
public class ScriptEditorService {

    private static final Logger log = LoggerFactory.getLogger(ScriptEditorService.class);

    /** 本项目只有一种布局：剧本行在库里，没有 standalone / character 目录之分。 */
    private static final String LAYOUT_STANDALONE = "standalone";

    /** 新建章节的初始事件，对齐原版（否则一保存就报「缺少章节结束」）。 */
    private static final String INITIAL_EVENTS =
            "[{\"type\":\"chapter_end\",\"end_type\":\"linear\",\"next_chapter\":\"end\"}]";

    /** 写入时允许出现在章节 id 各段里的字符：这些一律拒绝。 */
    private static final Set<Character> FORBIDDEN_ID_CHARS = Set.of('/', '\\', ':', '*', '?', '"', '<', '>', '|');

    /** 剧本编码规则（规格 E5a §1.1）：小写字母开头，2~64 位，只含小写字母/数字/_/-。 */
    private static final Pattern SCRIPT_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    /** 新建剧本时开场章节的缺省 id（与原版 story_config 的 intro_chapter 缺省值一致）。 */
    private static final String DEFAULT_INTRO_CHAPTER = "intro";

    /** 新建剧本时开场章节的显示名。 */
    private static final String DEFAULT_INTRO_NAME = "开场";

    /** 背景图认得的扩展名（与导入器一致，另外多收 bmp）。 */
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp");

    /** 外观页上传文件名的白名单（规格 E6a §8）：只收 webp，且不含路径分隔符。 */
    private static final Pattern APPEARANCE_BG_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+\\.webp$");

    /** 编辑器上传背景的落盘子目录（相对 frontend/public）与访问 url 前缀。 */
    private static final String EDITOR_BG_SUBDIR = "backgrounds/editor";
    private static final String EDITOR_BG_URL_PREFIX = "/backgrounds/editor/";

    private final InterviewScriptRepository scriptRepository;
    private final InterviewChapterRepository chapterRepository;
    private final InterviewSessionRepository sessionRepository;
    private final InterviewerRepository interviewerRepository;
    private final ObjectMapper objectMapper;
    private final ScriptValidator validator;

    /** 剧本自带素材的落盘根目录，与 {@code ScriptController} 同一个属性，必须一致。 */
    private final Path assetRoot;

    /**
     * frontend/public 根目录（规格 E6a §8）：由 {@code assetRoot}
     * （{@code public/backgrounds/scripts}）向上两级解析，与 {@code ScriptYamlImporter}
     * 复用同一条「backend 工作目录相对路径 + app.import.asset-target」解析逻辑。
     */
    private final Path publicRoot;

    public ScriptEditorService(InterviewScriptRepository scriptRepository,
                               InterviewChapterRepository chapterRepository,
                               InterviewSessionRepository sessionRepository,
                               InterviewerRepository interviewerRepository,
                               ObjectMapper objectMapper,
                               ScriptValidator validator,
                               @Value("${app.import.asset-target:../frontend/public/backgrounds/scripts}")
                               String assetTarget) {
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.sessionRepository = sessionRepository;
        this.interviewerRepository = interviewerRepository;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.assetRoot = Path.of(assetTarget).toAbsolutePath().normalize();
        this.publicRoot = this.assetRoot.getParent().getParent();
    }

    // ============================================================
    // 读
    // ============================================================

    /** 剧本列表，按 {@code order_num} 升序。 */
    public List<EditorScriptPackage> listScripts() {
        return scriptRepository.findAllByOrderByOrderNumAsc().stream()
                .map(this::toPackage)
                .toList();
    }

    /** 剧本详情：包信息 + 剧本设置 + 章节列表（order_num 升序）+ 素材索引。 */
    public EditorScriptDetail readScript(String key) {
        InterviewScript script = requireScript(key);
        List<EditorChapterSummary> chapters = chapterRepository
                .findByScriptIdOrderByOrderNumAsc(script.getId()).stream()
                .map(c -> new EditorChapterSummary(
                        c.getCode(), c.getName(), groupOf(c.getCode()), eventCount(c.getEvents())))
                .toList();
        // characters 本批恒为空 list（E3 CharactersTab 时补）
        return new EditorScriptDetail(
                toPackage(script, chapters.size()),
                storyConfig(script),
                chapters,
                assetIndex(key),
                List.of());
    }

    /** 单个章节，{@code events} 返回解析后的 JSON 数组。 */
    public EditorChapterContent readChapter(String key, String chapterId) {
        return content(requireChapter(requireScript(key), chapterId));
    }

    /**
     * 校验整个剧本（规格 E1b），委托 {@link ScriptValidator}。
     *
     * <p>章节按 {@code order_num} 升序传入，顺序即原版 {@code enumerate_chapter_ids} 的章节清单顺序。
     */
    public ValidationReportView validate(String key) {
        InterviewScript script = requireScript(key);
        return validator.validate(script, chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId()));
    }

    // ============================================================
    // 写
    // ============================================================

    /**
     * 覆盖一个章节。原版是「写临时文件 → fsync → rename，覆盖前留 .bak」，
     * 这里由 {@code @Transactional} 的单条 UPDATE + {@code events_backup} 列等价实现。
     */
    @Transactional
    public void writeChapter(String key, String chapterId, EditorWriteChapterRequest req) {
        InterviewChapter chap = requireChapter(requireScript(key), chapterId);

        List<JsonNode> events = req == null ? null : req.events();
        if (events == null) {
            throw new EditorBadRequestException("events 不能为空（允许空数组，但不能不传）");
        }
        // 原版 event.missing_type / event.not_a_map 的写入前置，不是校验器的替代
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            if (event == null || !event.isObject()
                    || !event.hasNonNull("type") || event.get("type").asText().isBlank()) {
                throw new EditorBadRequestException("第 " + (i + 1) + " 个事件缺少 type");
            }
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            throw new EditorBadRequestException("events 序列化失败: " + e.getOriginalMessage());
        }

        // 覆盖前留最近一份备份（对齐原版 .bak）
        chap.setEventsBackup(chap.getEvents());
        chap.setEvents(json);
        if (req.name() != null) {
            String trimmed = req.name().trim();
            // 原版 ChapterDoc.name 是 Option<String>，空串等价于不写；我们列非空，空串回落成章节 id
            chap.setName(trimmed.isEmpty() ? chapterId : trimmed);
        }
        chapterRepository.save(chap);
    }

    /** 新建章节，初始事件固定为只含一个 {@code chapter_end} 的数组。返回新建的章节内容。 */
    @Transactional
    public EditorChapterContent createChapter(String key, EditorCreateChapterRequest req) {
        InterviewScript script = requireScript(key);
        String raw = req == null ? null : req.chapterId();
        if (raw == null || raw.isBlank()) {
            throw new EditorBadRequestException("章节 id 不能为空");
        }
        String id = normalizeChapterId(raw);
        if (chapterRepository.findByScriptIdAndCode(script.getId(), id).isPresent()) {
            throw new EditorBadRequestException("章节已存在: '" + id + "'");
        }

        InterviewChapter chap = new InterviewChapter();
        chap.setScriptId(script.getId());
        chap.setCode(id);
        String name = req.name() == null ? "" : req.name().trim();
        chap.setName(name.isEmpty() ? id : name);
        chap.setOrderNum(chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId()).size() + 1);
        chap.setEvents(INITIAL_EVENTS);
        chapterRepository.save(chap);
        return content(chap);
    }

    /** 删除章节。原版也不做引用检查，留给校验器报 {@code graph.missing_target}。 */
    @Transactional
    public void deleteChapter(String key, String chapterId) {
        chapterRepository.delete(requireChapter(requireScript(key), chapterId));
    }

    /**
     * 新建剧本（规格 E5a §1.1）。
     *
     * <p>原版新建只是「mkdir + 写一份最简 story_config.yaml」；本项目对应写
     * {@code interview_script} 一行 + 一个开场章节 —— 少了开场章节新剧本一打开就校验不过
     * （intro 指向的章节不存在、也没有任何 {@code chapter_end}）。
     */
    @Transactional
    public EditorScriptPackage createScript(EditorCreateScriptRequest req) {
        String code = req == null || req.code() == null ? "" : req.code().trim();
        if (!SCRIPT_CODE_PATTERN.matcher(code).matches()) {
            throw new EditorBadRequestException(
                    "剧本编码不合法：'" + code + "'（要求小写字母开头，仅含小写字母/数字/_/-）");
        }
        if (scriptRepository.findByCode(code).isPresent()) {
            throw new EditorBadRequestException("剧本编码已存在：" + code);
        }

        String introChapter = introChapterId(req == null ? null : req.introChapter());
        String interviewerCode = req == null ? null : trimToNull(req.interviewerCode());
        Interviewer interviewer = null;
        if (interviewerCode != null) {
            interviewer = interviewerRepository.findByCode(interviewerCode)
                    .orElseThrow(() -> new EditorBadRequestException("面试官不存在: '" + interviewerCode + "'"));
        }

        InterviewScript script = new InterviewScript();
        script.setCode(code);
        String scriptName = req == null ? null : trimToNull(req.scriptName());
        script.setScriptName(scriptName == null ? code : scriptName);
        script.setDescription(req == null ? null : trimToNull(req.description()));
        script.setIntroChapter(introChapter);
        script.setJobCode(req == null ? null : trimToNull(req.jobCode()));
        script.setLevel(req == null ? null : trimToNull(req.level()));
        script.setCompanyStyle(req == null ? null : trimToNull(req.companyStyle()));
        script.setInterviewerId(interviewer == null ? null : interviewer.getId());
        script.setOrderNum(nextScriptOrderNum());
        script.setTriggerMode("manual");
        script.setEnabled(Boolean.TRUE);
        script.setCreatedAt(LocalDateTime.now());
        // scriptSettings / characters 保持 null：新剧本没有剧本设置、也没有角色目录
        scriptRepository.save(script);

        InterviewChapter intro = new InterviewChapter();
        intro.setScriptId(script.getId());
        intro.setCode(introChapter);
        intro.setName(DEFAULT_INTRO_NAME);
        intro.setOrderNum(1);
        // 与原版新建章节同理：自带一条「章节结束」，免得一保存就报缺少结束事件
        intro.setEvents(INITIAL_EVENTS);
        chapterRepository.save(intro);

        return toPackage(script);
    }

    /**
     * 删除整个剧本（规格 E5a §1.2）。
     *
     * <p>顺序不能换：{@code interview_session.script_id} 是普通列、没有外键约束，
     * 不先解绑的话历史场次里会留下悬空 id，用户点「开始面试」时才报剧本不存在。
     */
    @Transactional
    public void deleteScript(String key) {
        InterviewScript script = requireScript(key);

        List<InterviewSession> sessions = sessionRepository.findByScriptId(script.getId());
        for (InterviewSession session : sessions) {
            session.setScriptId(null);
            sessionRepository.save(session);
        }

        chapterRepository.deleteByScriptId(script.getId());
        scriptRepository.delete(script);
    }

    /** 剧本设置页保存：只接受白名单键，其余忽略。 */
    @Transactional
    public void writeConfig(String key, EditorWriteConfigRequest req) {
        InterviewScript script = requireScript(key);
        Map<String, Object> config = req == null ? null : req.config();
        if (config == null) {
            throw new EditorBadRequestException("config 不能为空");
        }

        if (config.containsKey("script_name")) {
            String name = stringValue(config.get("script_name"));
            if (name == null || name.isBlank()) {
                throw new EditorBadRequestException("剧本名不能为空");
            }
            script.setScriptName(name.trim());
        }
        if (config.containsKey("description")) {
            script.setDescription(stringValue(config.get("description")));
        }
        if (config.containsKey("recommand_start")) {
            script.setRecommandStart(stringValue(config.get("recommand_start")));
        }
        // intro_chapter 指向的章节不存在也不拒绝（原版同样不拒绝，留给校验器报 config.intro_missing）
        if (config.containsKey("intro_chapter")) {
            script.setIntroChapter(stringValue(config.get("intro_chapter")));
        }
        if (config.containsKey("job_code")) {
            script.setJobCode(stringValue(config.get("job_code")));
        }
        if (config.containsKey("level")) {
            script.setLevel(stringValue(config.get("level")));
        }
        if (config.containsKey("company_style")) {
            script.setCompanyStyle(stringValue(config.get("company_style")));
        }
        // 面试官：前端给的是 code，表里存的是 interviewer_id。原版 story_config 没有这个概念，
        // 是本项目把「剧本绑定面试官」落到外键上的做法（E3 补）。空串 = 解绑。
        if (config.containsKey("interviewer_code")) {
            String code = stringValue(config.get("interviewer_code"));
            if (code == null || code.isBlank()) {
                script.setInterviewerId(null);
            } else {
                String trimmed = code.trim();
                Interviewer interviewer = interviewerRepository.findByCode(trimmed)
                        .orElseThrow(() -> new EditorBadRequestException("面试官不存在: '" + trimmed + "'"));
                script.setInterviewerId(interviewer.getId());
            }
        }
        if (config.containsKey("script_settings")) {
            script.setScriptSettings(scriptSettingsValue(config.get("script_settings")));
        }
        scriptRepository.save(script);
    }

    // ============================================================
    // 素材索引
    // ============================================================

    /**
     * 扫 {@code <assetTarget>/<key>/} 这一层（导入器就是扁平落盘的，见
     * {@code ScriptYamlImporter.publishAssets}）。
     *
     * <p>限制说明：本项目导入器只搬背景图，所以 {@code music} / {@code sound} / {@code ambient}
     * 恒为空 list；也没有独立的 {@code Assets/Pics/}，{@code pic} 与 {@code background} 共用一份。
     * 这是导入器的范围限制，不是编辑器 bug。目录不存在时全部空 list，不报错。
     */
    public EditorAssetIndex assetIndex(String key) {
        Path dir = assetRoot.resolve(key).normalize();
        if (!dir.startsWith(assetRoot) || !Files.isDirectory(dir)) {
            return emptyAssets();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<String> images = files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(ScriptEditorService::isImage)
                    .sorted()
                    .toList();
            return new EditorAssetIndex(images, List.of(), List.of(), List.of(), images);
        } catch (IOException e) {
            log.warn("[ScriptEditorService] 读取素材目录失败 {}：{}", dir, e.getMessage());
            return emptyAssets();
        }
    }

    private static EditorAssetIndex emptyAssets() {
        return new EditorAssetIndex(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static boolean isImage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private InterviewScript requireScript(String key) {
        return scriptRepository.findByCode(key)
                .orElseThrow(() -> new EditorNotFoundException("剧本不存在: '" + key + "'"));
    }

    private InterviewChapter requireChapter(InterviewScript script, String chapterId) {
        return chapterRepository.findByScriptIdAndCode(script.getId(), chapterId)
                .orElseThrow(() -> new EditorNotFoundException("章节不存在: '" + chapterId + "'"));
    }

    private EditorScriptPackage toPackage(InterviewScript script) {
        return toPackage(script, chapterRepository.findByScriptIdOrderByOrderNumAsc(script.getId()).size());
    }

    private EditorScriptPackage toPackage(InterviewScript script, int chapterCount) {
        // 本项目没有 character/<角色>/ 布局：layout 恒 standalone，boundCharacterFolder 恒 null
        return new EditorScriptPackage(
                script.getCode(),
                LAYOUT_STANDALONE,
                script.getCode(),
                null,
                script.getScriptName(),
                script.getDescription(),
                // 面试剧本不是「羁绊冒险」，恒 false（装箱类型，保证 JSON 键就是 isAdventure）
                Boolean.FALSE,
                chapterCount,
                Boolean.TRUE.equals(script.getEnabled()));
    }

    /** 把 {@code interview_script} 的列摊平成原版 story_config.yaml 的键名（snake_case）。 */
    private Map<String, Object> storyConfig(InterviewScript script) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("script_name", script.getScriptName());
        config.put("description", script.getDescription());
        config.put("recommand_start", script.getRecommandStart());
        config.put("intro_chapter", script.getIntroChapter());
        config.put("job_code", script.getJobCode());
        config.put("level", script.getLevel());
        config.put("company_style", script.getCompanyStyle());
        config.put("interviewer_code", interviewerCode(script.getInterviewerId()));
        config.put("script_settings", parseSettings(script.getScriptSettings()));
        return config;
    }

    private String interviewerCode(Long interviewerId) {
        if (interviewerId == null) {
            return null;
        }
        return interviewerRepository.findById(interviewerId).map(Interviewer::getCode).orElse(null);
    }

    /** script_settings 列是字符串：能解析成对象就摊开，解析失败给空对象。 */
    private Object parseSettings(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("[ScriptEditorService] script_settings 不是合法 JSON，按空对象返回：{}", e.getOriginalMessage());
            return Map.of();
        }
    }

    private EditorChapterContent content(InterviewChapter chap) {
        return new EditorChapterContent(chap.getCode(), chap.getName(), parseEvents(chap.getEvents()));
    }

    private List<JsonNode> parseEvents(String json) {
        if (json == null || json.isBlank()) {
            throw new EditorBadRequestException("events 不是合法 JSON 数组: 内容为空");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new EditorBadRequestException("events 不是合法 JSON 数组: " + e.getOriginalMessage());
        }
        if (node == null || !node.isArray()) {
            throw new EditorBadRequestException("events 不是合法 JSON 数组: 顶层不是数组");
        }
        List<JsonNode> events = new ArrayList<>(node.size());
        node.forEach(events::add);
        return events;
    }

    /** 解析失败按 0 计（列表页不该因为一个坏章节整体 500）。 */
    private int eventCount(String json) {
        try {
            return parseEvents(json).size();
        } catch (EditorBadRequestException e) {
            return 0;
        }
    }

    /** 原版 chapter_summaries：id 里第一个 {@code /} 之前那段就是分组名。 */
    private static String groupOf(String chapterId) {
        if (chapterId == null) {
            return null;
        }
        int slash = chapterId.indexOf('/');
        return slash > 0 ? chapterId.substring(0, slash) : null;
    }

    /** 章节 id 按 {@code /} 分段校验，逐段去掉首尾空白后再拼回去。 */
    private static String normalizeChapterId(String raw) {
        String[] segments = raw.split("/", -1);
        List<String> cleaned = new ArrayList<>(segments.length);
        for (String segment : segments) {
            String s = segment.trim();
            if (s.isEmpty()) {
                throw new EditorBadRequestException("章节 id 不能有空白段: '" + raw + "'");
            }
            if (".".equals(s) || "..".equals(s)) {
                throw new EditorBadRequestException("章节 id 不能是 '.' 或 '..': '" + raw + "'");
            }
            for (int i = 0; i < s.length(); i++) {
                if (FORBIDDEN_ID_CHARS.contains(s.charAt(i))) {
                    throw new EditorBadRequestException(
                            "章节 id 不能包含 " + s.charAt(i) + ": '" + raw + "'");
                }
            }
            cleaned.add(s);
        }
        return String.join("/", cleaned);
    }

    /**
     * 开场章节 id：trim → 去掉结尾 {@code .yaml} → 空则用 {@code intro} → 查禁用字符。
     *
     * <p>不允许 {@code /}：开场章节是单个 id，带子目录的章节由 {@code createChapter} 负责。
     */
    private static String introChapterId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.endsWith(".yaml")) {
            value = value.substring(0, value.length() - ".yaml".length()).trim();
        }
        if (value.isEmpty()) {
            return DEFAULT_INTRO_CHAPTER;
        }
        for (int i = 0; i < value.length(); i++) {
            if (FORBIDDEN_ID_CHARS.contains(value.charAt(i))) {
                throw new EditorBadRequestException("开场章节 id 不合法：'" + value + "'");
            }
        }
        return value;
    }

    /** 新剧本排在列表末尾：现有最大 order_num + 1，空表从 1 开始。 */
    private int nextScriptOrderNum() {
        List<InterviewScript> all = scriptRepository.findAllByOrderByOrderNumAsc();
        if (all.isEmpty()) {
            return 1;
        }
        Integer last = all.get(all.size() - 1).getOrderNum();
        return (last == null ? 0 : last) + 1;
    }

    /** trim；null 或全空白都折成 null（对应「空串存 null」）。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 请求体里的标量转字符串；对象/数组拒收（描述类字段没有结构化语义）。 */
    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            throw new EditorBadRequestException("该字段只能是文本");
        }
        return String.valueOf(value);
    }

    /** script_settings：对象 → 序列化成 JSON 字符串存列；字符串 → 原样存。 */
    private String scriptSettingsValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new EditorBadRequestException("script_settings 序列化失败: " + e.getOriginalMessage());
        }
    }

    // ============================================================
    // 外观（规格 E6a §8）：编辑器背景上传 / 全局背景清单
    // ============================================================

    /**
     * 上传一张裁剪后的编辑器背景：{@code dataUrl} 解码后落盘到
     * {@code frontend/public/backgrounds/editor/<name>}，返回可直接访问的 url。
     *
     * <p>校验：{@code name} 必须匹配 {@code ^[A-Za-z0-9_-]+\.webp$}；{@code dataUrl}
     * 必须以 {@code data:image/} 开头、逗号后能 base64 解码。任一不满足即 400。
     */
    public EditorAssetFileView uploadEditorBackground(String dataUrl, String name) {
        if (name == null || !APPEARANCE_BG_NAME_PATTERN.matcher(name).matches()) {
            throw new EditorBadRequestException("文件名不合法（只允许 [A-Za-z0-9_-] 且以 .webp 结尾）：" + name);
        }
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
            throw new EditorBadRequestException("dataUrl 必须以 data:image/ 开头");
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            throw new EditorBadRequestException("dataUrl 缺少 base64 数据段");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(dataUrl.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new EditorBadRequestException("dataUrl 的 base64 数据段解码失败");
        }
        Path dir = publicRoot.resolve(EDITOR_BG_SUBDIR);
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), bytes);
        } catch (IOException e) {
            throw new EditorBadRequestException("背景写入失败：" + e.getMessage());
        }
        log.info("[ScriptEditorService] 编辑器背景已落盘 {}（{} 字节）", dir.resolve(name), bytes.length);
        return new EditorAssetFileView(name, EDITOR_BG_URL_PREFIX + name, bytes.length);
    }

    /**
     * 合并三处全局背景清单（规格 E6a §8）：内置三张 → {@code backgrounds/scripts/*} →
     * {@code backgrounds/editor/*}，按 url 排序。任一目录缺失跳过，不报错。
     */
    public List<EditorAssetFileView> listBackgrounds() {
        List<EditorAssetFileView> out = new ArrayList<>();
        // 1. 内置三张：url 即 ResourceMapping.BACKGROUNDS 的 value，name 取文件名，size 读真实文件
        for (Map.Entry<String, String> e : ResourceMapping.BACKGROUNDS.entrySet()) {
            String url = e.getValue();
            Path file = publicRoot.resolve(url.substring(1));
            try {
                out.add(new EditorAssetFileView(
                        file.getFileName().toString(), url, Files.size(file)));
            } catch (IOException ignored) {
                // 文件缺失（如无 LFS 的指针文件）跳过，不报错
            }
        }
        // 2. 剧本自带：backgrounds/scripts/<code>/<file>
        collectBackgroundImages(publicRoot.resolve("backgrounds").resolve("scripts"),
                "/backgrounds/scripts/", out);
        // 3. 编辑器上传：backgrounds/editor/<file>
        collectBackgroundImages(publicRoot.resolve(EDITOR_BG_SUBDIR), EDITOR_BG_URL_PREFIX, out);
        out.sort(Comparator.comparing(EditorAssetFileView::url));
        return out;
    }

    /** 目录下每张图片一条记录；目录缺失 / 不可读整段跳过，单个文件读大小失败也跳过。 */
    private static void collectBackgroundImages(Path dir, String urlPrefix,
                                                List<EditorAssetFileView> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.sorted().toList()) {
                String fileName = p.getFileName().toString();
                if (!Files.isRegularFile(p) || !ResourceMapping.isImage(fileName)) {
                    continue;
                }
                try {
                    out.add(new EditorAssetFileView(fileName, urlPrefix + fileName, Files.size(p)));
                } catch (IOException ignored) {
                    // 读不到大小的文件跳过
                }
            }
        } catch (IOException ignored) {
            // 目录不可读整段跳过
        }
    }
}

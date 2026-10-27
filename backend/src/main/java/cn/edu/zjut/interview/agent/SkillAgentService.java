package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.domain.AgentConversation;
import cn.edu.zjut.interview.domain.AgentMessage;
import cn.edu.zjut.interview.domain.InterviewScript;
import cn.edu.zjut.interview.editor.ScriptValidator;
import cn.edu.zjut.interview.llm.LlmOptions;
import cn.edu.zjut.interview.repository.AgentConversationRepository;
import cn.edu.zjut.interview.repository.AgentMessageRepository;
import cn.edu.zjut.interview.repository.InterviewChapterRepository;
import cn.edu.zjut.interview.repository.InterviewScriptRepository;
import cn.edu.zjut.interview.repository.UserSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 剧本编辑器 AI 助手（Skill Agent）的服务层，对应原版
 * {@code crates/ling-chat-main/src/ai_service/skill_agent/} 的
 * {@code db.rs}（会话/消息 CRUD）+ {@code mod.rs::SkillAgentState}（共享可变状态）。
 *
 * <p>状态部分（原版 {@code SkillAgentState}）三件套：
 * <ul>
 *   <li><b>审批待决 map</b>：{@code request_id → CompletableFuture<Boolean>}，
 *       工具执行器（E7a2）注册，{@code POST /approvals/{requestId}} 决断；</li>
 *   <li><b>取消标志</b>：全局 {@link AtomicBoolean}，{@code /stop} 置位、运行循环每步检查；</li>
 *   <li><b>任务句柄</b>：会话 id → {@link Future}，{@code /stop} 用来 abort 这一轮。</li>
 * </ul>
 *
 * <p>{@code start_chat}（E7a2 起）是<b>真实的 LLM 工具调用循环</b>：落用户消息 → 推 status →
 * 路由定工具白名单 → 流式补全（reasoning / message_delta）→ 工具调用循环（tool_call / tool_result，
 * 受 maxToolRounds 限制）→ done（带 usage）；LLM 不可用走降级路径（status 说明原因 + done）。
 */
@Service
public class SkillAgentService {

    private static final Logger log = LoggerFactory.getLogger(SkillAgentService.class);

    /**
     * SSE 连接的兜底超时。
     *
     * <p><b>剧本助手这一处必须是不超时（0）</b>，不能跟 {@code ScriptPlayController} /
     * {@code ChatService} 一样用 180s：一轮会路由出多项串行任务（大纲 → 各章正文 → 各章转 YAML），
     * 实测「随便写 3 章直接落盘」= 7 项、约 40s/项，总时长必然超过任何固定超时。
     * 2026-10-27 实测：180s 时第 5/7 项刚开口，Spring 就抛 AsyncRequestTimeoutException 把流
     * 强制 complete，后续 send 全部 IllegalStateException，整轮崩在半路（三章正文写完、
     * YAML 一个没落）。
     *
     * <p>0 = 不过期，连接由正常 complete / 出错 / 客户端断开收尾；sendQuietly 已挡住
     * 「流已关闭仍要发」的二次异常。
     */
    private static final long SSE_TIMEOUT_MILLIS = 0L;

    /** 技能 SKILL.md 的大小上限（对齐 {@code skills.rs::MAX_SKILL_BYTES}）。 */
    private static final long MAX_SKILL_BYTES = 512 * 1024L;

    /** 单份流程产物的大小上限（对齐 {@code agent.rs::ARTIFACT_MAX_BYTES}）：超了就不列内容。 */
    private static final long ARTIFACT_MAX_BYTES = 64 * 1024L;

    /** 原版 {@code skills.rs::MAX_SKILLS}。 */
    private static final int MAX_SKILLS = 200;

    /** 反推剧本 key 用的路径标记（原版 {@code db.rs} 的 {@code like("%/scripts/%")}）。 */
    private static final String SCRIPT_PATH_MARKER = "/scripts/";

    /**
     * 同一工具同一参数连续重复多少次就熔断（<b>本项目补充</b>，原版没有这条）。
     *
     * <p>原版 {@code max_tool_rounds} 默认 {@code -1}（不限，见 {@code config.rs:42}），
     * 所以连续重复的循环只能靠这条挡：实测会话 26 模型为找"为什么说我没写"连调
     * {@code list_files}/{@code read_file} 上百次，最后 SSE 崩掉。
     */
    private static final int REPEAT_TOOL_LIMIT = 5;

    /** {@code .agent/} 目录名（对齐 {@code stage/mod.rs:13 AGENT_DIR}）。 */
    private static final String AGENT_DIR = ".agent";

    /**
     * 边界底线（对齐 {@code core.rs::BOUNDARY_BASELINE = include_str!("prompts/role_boundary_baseline.md")}）。
     *
     * <p>四个角色共用同一份，且必须在 system prompt <b>最前面</b>：它是"没让你做的别做"这一条
     * 硬约束，任何角色的流程描述都不能覆盖它。用 {@link Class#getResourceAsStream} 读，
     * 因为它要跟着 jar 走（资源目录里的 .md 会被打进 jar，用 {@link Path} 读不到）。
     */
    private static final String BOUNDARY_BASELINE = loadClasspathText("/agent/prompts/role_boundary_baseline.md");

    /**
     * 「我这一项是什么」（对齐 {@code core.rs:252} 的 {@code include_str!("prompts/core_run_chat.md")}）：
     * 三个 {@code {}} 由 {@link #renderCoreRunChat} 顺序填。
     */
    private static final String CORE_RUN_CHAT = loadClasspathText("/agent/prompts/core_run_chat.md");

    /**
     * 边界补充的长度上限（对齐 {@code role.rs::BOUNDARY_MAX_CHARS}，按<b>字符</b>数不是字节数）。
     *
     * <p>给 {@code sanitize_boundary()} 用：角色可以补一句"更谨慎"的边界，但补的那句超长、
     * 或出现"顺便/也把/补上"这类放宽词，整条补充丢弃、只用底线。当前在 {@link Role} 里统一实现。
     */
    static final int BOUNDARY_MAX_CHARS = 40;

    /** 一出现就说明它在给自己放宽范围，整条补充丢弃（对齐 {@code role.rs::BOUNDARY_FORBIDDEN}）。 */
    static final String[] BOUNDARY_FORBIDDEN = {"顺便", "也把", "补上", "一起写", "直接写完", "不用问"};

    /**
     * 读 classpath 上的文本资源；读不到返回空串（缺文件不该让整轮崩掉）。
     *
     * <p>静态初始化用，不能依赖注入进来的 ObjectMapper / 配置。
     */
    private static String loadClasspathText(String resource) {
        try (var in = SkillAgentService.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("[SkillAgentService] 缺资源 {}", resource);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SkillAgentService] 读资源失败 {}：{}", resource, e.toString());
            return "";
        }
    }

    /** 默认系统提示（config.systemPrompt 为空时使用）。 */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是剧本编辑器内置的 AI 助手。用户会用自然语言让你编写或修改面试剧本：设计稿、章节正文、\
            或转成能跑的章节 YAML。有工具可用时优先用工具做事，做完再用一段话向用户汇报；\
            没有合适的工具就如实说明，不要装作做过了。""";

    private final AgentConversationRepository conversationRepository;
    private final AgentMessageRepository messageRepository;
    private final UserSettingRepository userSettings;
    private final InterviewScriptRepository scriptRepository;
    private final InterviewChapterRepository chapterRepository;
    private final ConfigService configs;
    private final ObjectMapper objectMapper;
    private final ScriptValidator validator;
    private final SkillAgentLlmClient llmClient;
    private final SkillAgentRouter router;
    /** 落盘桥接：章节 YAML → {@code interview_chapter} 并回自检。 */
    private final ChapterLander lander;

    /** 项目数据根目录（原版 {@code data_dir::get_data_dir()}，本项目即 {@code game_data/} 的上一级）。 */
    private final Path dataDir;

    /** {@code game_data/} 目录（技能库 {@code game_data/skills} 的父目录）。 */
    private final Path gameDataDir;

    /** 一轮对话的后台线程（SSE 边跑边推，与引擎池同规格但独立命名）。 */
    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "skill-agent-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    /** 待审批的命令：request_id → 决断结果（{@code true} = 允许）。 */
    private final Map<String, CompletableFuture<Boolean>> approvals = new ConcurrentHashMap<>();

    /** 全局取消标志（跨一次运行共享）。 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 当前运行的任务句柄：会话 id → Future（{@code /stop} abort 用）。 */
    private final Map<Long, Future<?>> tasks = new ConcurrentHashMap<>();

    public SkillAgentService(AgentConversationRepository conversationRepository,
                              AgentMessageRepository messageRepository,
                              UserSettingRepository userSettings,
                              InterviewScriptRepository scriptRepository,
                              InterviewChapterRepository chapterRepository,
                              ConfigService configs,
                              ObjectMapper objectMapper,
                              ScriptValidator validator,
                              SkillAgentLlmClient llmClient,
                              ChapterLander lander,
                              @Value("${app.characters-dir}") String charactersDir) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userSettings = userSettings;
        this.scriptRepository = scriptRepository;
        this.chapterRepository = chapterRepository;
        this.configs = configs;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.llmClient = llmClient;
        this.lander = lander;
        this.router = new SkillAgentRouter(llmClient, configs, objectMapper);
        // app.characters-dir 指向 game_data/characters，往上一级即 game_data/；
        // 沙箱根（原版 data_dir）就是 game_data/：剧本包在 game_data/scripts/<key>，
        // 技能库在 game_data/skills，模型写 "game_data/scripts/<key>/.agent/…" 正好落在沙箱内。
        // 不能再往上取一级：那是项目根的父目录，会把 scripts/<key> 推到项目外。
        Path characters = resolveExistingDir(charactersDir);
        this.gameDataDir = characters.getParent();
        this.dataDir = this.gameDataDir == null ? characters : this.gameDataDir;
    }

    // ============================================================
    // 设置（editor_agent_get_settings / editor_agent_save_settings / editor_agent_get_default_dirs）
    // ============================================================

    /** Agent 设置（沙箱目录为空表示默认 {@code data/}）。 */
    public AgentSettings getSettings() {
        return AgentSettings.from(SkillAgentConfig.load(configs, userSettings));
    }

    /** 保存设置，回读一遍给前端（存进去的才是生效的）。 */
    public AgentSettings saveSettings(AgentSettings request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设置不能为空");
        }
        SkillAgentConfig config = request.toConfig();
        config.save(configs, userSettings);
        return AgentSettings.from(SkillAgentConfig.load(configs, userSettings));
    }

    /** 设置面板展示的三个目录。 */
    public AgentDefaultDirs getDefaultDirs() {
        SkillAgentConfig config = SkillAgentConfig.load(configs, userSettings);
        return new AgentDefaultDirs(
                dataDir.toString(),
                config.resolveSkillsDir(gameDataDir).toString(),
                config.resolveSandboxDir(dataDir).toString());
    }

    // ============================================================
    // 技能（editor_agent_list_skills / editor_agent_read_skill）
    // ============================================================

    /**
     * 发现全部技能（对齐 {@code skills.rs::find_all_skills}）：技能根 + 两个隐藏子目录
     * （兼容 {@code .agent}/ {@code .claude} 布局），按名去重，项目内优先再按名排序。
     *
     * <p>目录不存在（本项目 {@code game_data/skills} 还没初始化）时返回空数组，不是错误。
     */
    public List<SkillInfo> listSkills() {
        SkillAgentConfig config = SkillAgentConfig.load(configs, userSettings);
        Path skillsRoot = config.resolveSkillsDir(gameDataDir);
        List<SkillInfo> skills = new ArrayList<>();
        for (Path dir : searchDirs(skillsRoot)) {
            if (skills.size() >= MAX_SKILLS || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    if (skills.size() >= MAX_SKILLS || !Files.isDirectory(entry) || Files.isSymbolicLink(entry)) {
                        continue;
                    }
                    String name = entry.getFileName().toString();
                    if (skills.stream().anyMatch(s -> s.name().equals(name))) {
                        continue;
                    }
                    Path skillMd = safeSkillFile(entry);
                    if (skillMd == null) {
                        continue;
                    }
                    String content = readFile(skillMd);
                    if (content == null) {
                        continue;
                    }
                    // 原版：路径以技能根开头算 project，否则 global
                    String location = entry.startsWith(skillsRoot) ? "project" : "global";
                    skills.add(new SkillInfo(name, extractYamlField(content, "description"),
                            location, entry.toString()));
                }
            } catch (IOException e) {
                log.warn("[SkillAgentService] 读取技能目录失败 {}：{}", dir, e.getMessage());
            }
        }
        skills.sort(Comparator.comparing((SkillInfo s) -> !"project".equals(s.location()))
                .thenComparing(SkillInfo::name));
        return skills;
    }

    /** 按名读取技能内容；不存在 404（原版是 {@code Err("未找到技能: x")}）。 */
    public SkillContent readSkill(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "技能名不合法：" + name);
        }
        SkillAgentConfig config = SkillAgentConfig.load(configs, userSettings);
        for (Path dir : searchDirs(config.resolveSkillsDir(gameDataDir))) {
            Path skillMd = safeSkillFile(dir.resolve(name).normalize());
            if (skillMd == null) {
                continue;
            }
            String content = readFile(skillMd);
            if (content != null) {
                return new SkillContent(name, skillMd.getParent().toString(), content);
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到技能: " + name);
    }

    /** 技能搜索目录（对齐 {@code skills.rs::search_dirs}）。 */
    private static List<Path> searchDirs(Path skillsRoot) {
        return List.of(skillsRoot,
                skillsRoot.resolve(".agent").resolve("skills"),
                skillsRoot.resolve(".claude").resolve("skills"));
    }

    private static Path safeSkillFile(Path skillDir) {
        Path skillMd = skillDir.resolve("SKILL.md").normalize();
        if (!skillMd.startsWith(skillDir.normalize()) || !Files.isRegularFile(skillMd)) {
            return null;
        }
        try {
            return Files.size(skillMd) <= MAX_SKILL_BYTES ? skillMd : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 从 YAML frontmatter 取一个字段（对齐 {@code skills.rs::extract_yaml_field}）。 */
    private static String extractYamlField(String content, String field) {
        if (!content.stripLeading().startsWith("---")) {
            return "";
        }
        String prefix = field + ":";
        for (String line : content.lines().skip(1).toList()) {
            String trimmed = line.trim();
            if (trimmed.equals("---")) {
                break;
            }
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim()
                        .replace("\"", "").replace("'", "");
            }
        }
        return "";
    }

    // ============================================================
    // 会话（create / list / delete / rename / resolve_script_key）
    // ============================================================

    /** 新建会话（title 为原版没有的扩展；script_key 只记录绑定，不存快照）。 */
    @Transactional
    public ConversationInfo createConversation(String title, String scriptKey) {
        LocalDateTime now = LocalDateTime.now();
        AgentConversation conversation = new AgentConversation();
        conversation.setTitle(title == null || title.isBlank() ? null : title.trim());
        conversation.setScriptKey(scriptKey == null || scriptKey.isBlank() ? null : scriptKey.trim());
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        AgentConversation saved = conversationRepository.save(conversation);
        log.info("[SkillAgentService] 新建会话 {}（scriptKey={}）", saved.getId(), saved.getScriptKey());
        return toInfo(saved);
    }

    /** 会话列表：最近更新在前。 */
    public List<ConversationInfo> listConversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toInfo)
                .toList();
    }

    /** 删会话连带删它的消息（对齐 {@code db.rs::delete_conversation}）；不存在 404。 */
    @Transactional
    public void deleteConversation(Long conversationId) {
        AgentConversation conversation = requireConversation(conversationId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conversation);
        log.info("[SkillAgentService] 删除会话 {}", conversationId);
    }

    /** 重命名：空标题拒绝（原版同款：避免「清空标题」后首轮又自动命名）。 */
    @Transactional
    public void renameConversation(Long conversationId, String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标题不能为空");
        }
        AgentConversation conversation = requireConversation(conversationId);
        conversation.setTitle(title.trim());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
    }

    /**
     * 会话归属的剧本 key；库里没绑定时从历史写入路径反推（对齐 {@code db.rs::derive_script_key}）。
     *
     * <p>反推用的是 {@code write_file} 工具参数里的 {@code path}：命中 {@code /scripts/<key>/}
     * 且 key 在 {@code interview_script.code} 里才算一票，取票数最多的。
     */
    public String resolveScriptKey(Long conversationId) {
        AgentConversation conversation = requireConversation(conversationId);
        return conversation.getScriptKey() != null
                ? conversation.getScriptKey()
                : deriveScriptKey(conversationId);
    }

    // ============================================================
    // 消息（get_messages / clear / artifacts）
    // ============================================================

    /** 某会话的全部消息，按 id 升序。 */
    public List<PersistedMessage> getMessages(Long conversationId) {
        requireConversation(conversationId);
        return messageRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(this::toMessage)
                .toList();
    }

    /**
     * 当前会话的剧本包目录：{@code <sandbox>/scripts/<scriptKey>}；没有 key 返回 {@code null}。
     *
     * <p>全项目<b>唯一</b>的剧本包目录推导，章节 / 设计稿 / 细节稿 / 队列都从它算（对齐原版
     * {@code stage::derive}：{@code script_paths::resolve_script_dir(key)} → {@code script_dir/.agent/*}，
     * 以及 {@code core.rs::queue_dir}「有快照用快照，没有就用 key 推导」）。
     *
     * <p><b>不做「包目录不存在就退回沙箱根」的兜底</b>：{@code .agent/} 永远在剧本包内，不在沙箱根。
     * 包目录还不存在说明这一轮该先建包，{@code write_file} 写 {@code .agent/} 时会自动建父目录。
     */
    static Path scriptDir(Path sandboxDir, String scriptKey) {
        if (sandboxDir == null || scriptKey == null || scriptKey.isBlank()) {
            return null;
        }
        return sandboxDir.resolve("scripts").resolve(scriptKey).normalize();
    }

    /** 清空某会话的消息（会话本身保留）。 */
    @Transactional
    public void clearConversation(Long conversationId) {
        requireConversation(conversationId);
        messageRepository.deleteByConversationId(conversationId);
        touchConversation(conversationId);
        log.info("[SkillAgentService] 清空会话 {} 的消息", conversationId);
    }

    /**
     * 列出会话所属剧本包 {@code .agent/} 下的流程产物（对齐 {@code agent.rs::read_artifacts}）。
     *
     * <p>本项目的剧本包在库里（{@code interview_script}），磁盘目录不一定存在，
     * 读不到就返回空数组（原版同款：{@code read_dir} 失败返回空 Vec）。
     */
    public List<AgentArtifact> listArtifacts(Long conversationId) {
        String scriptKey = resolveScriptKey(conversationId);
        if (scriptKey == null) {
            return List.of();
        }
        Path agentDir = scriptDir(gameDataDir, scriptKey).resolve(AGENT_DIR);
        if (!Files.isDirectory(agentDir)) {
            return List.of();
        }
        List<AgentArtifact> artifacts = new ArrayList<>();
        try (Stream<Path> entries = Files.list(agentDir)) {
            for (Path entry : entries.sorted(Comparator.comparing(Path::toString)).toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || !name.endsWith(".md") || !Files.isRegularFile(entry)) {
                    continue;
                }
                long size;
                try {
                    size = Files.size(entry);
                } catch (IOException e) {
                    continue;
                }
                String content = readFile(entry);
                if (content == null) {
                    continue;
                }
                artifacts.add(new AgentArtifact(name, size > ARTIFACT_MAX_BYTES ? "" : content));
            }
        } catch (IOException e) {
            log.warn("[SkillAgentService] 读取流程产物失败 {}：{}", agentDir, e.getMessage());
        }
        // 排序对齐原版 read_artifacts：设计稿恒排第一，其余按文件名（原版是「是不是设计稿」再按 name）。
        String designName = ScriptFacts.DESIGN_REL_PATH.substring(ScriptFacts.DESIGN_REL_PATH.lastIndexOf('/') + 1);
        artifacts.sort(Comparator
                .comparing((AgentArtifact a) -> !a.name().equals(designName))
                .thenComparing(AgentArtifact::name));
        return artifacts;
    }

    // ============================================================
    // 对话（start_chat 骨架 / stop_chat / rewind / resolve_approval）
    // ============================================================

    /**
     * 开始一轮对话（本批骨架）：落用户消息 → 推 status / message_delta / done。
     *
     * <p>返回已接上的 {@link SseEmitter}；会话不存在在开流之前就 404，
     * 不把参数问题变成流里的 error 事件。
     *
     * <p>消息的三种取值：{@code null}（没带 body）按骨架演示走——不落用户消息、照推三条事件
     * （规格 6.3 的验收 curl 就不带 body）；空白串 400「消息不能为空」（对齐原版
     * {@code start_chat} 的前置校验）；正常文本照常落库。
     */
    @Transactional
    public SseEmitter startChat(Long conversationId, String message) {
        if (message != null && message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        AgentConversation conversation = requireConversation(conversationId);
        // 原版：库里没绑剧本 key 时先反推，反推到了顺手补绑（保持列表排序）
        if (conversation.getScriptKey() == null) {
            String derived = deriveScriptKey(conversationId);
            if (derived != null) {
                conversation.setScriptKey(derived);
            }
        }
        Long userMessageId = null;
        if (message != null) {
            AgentMessage userMessage = insertMessage(conversationId, "user", message.trim(), null, null, null, null);
            userMessageId = userMessage.getId();
            touchConversation(conversationId);
        } else {
            log.info("[SkillAgentService] 会话 {} 收到无 body 的 chat 请求，按骨架只推事件", conversationId);
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        cancelled.set(false);
        Future<?> task = chatExecutor.submit(() -> {
            try {
                runChatTurn(conversationId, message == null ? "" : message.trim(), emitter);
            } catch (Exception e) {
                log.warn("[SkillAgentService] 会话 {} 对话循环失败：{}", conversationId, e.toString());
                // 兜底降级：任何没接住的异常也要收成 status + done，不许空流 / 挂住 / 500。
                // 这里只能走 sendQuietly：流可能已经 complete（例如 SSE 超时或客户端已断开），
                // 直接 send 会再抛 IllegalStateException("ResponseBodyEmitter has already completed")，
                // 从 chatExecutor 的任务里冒出去。
                sendQuietly(emitter, SkillAgentEvent.status("对话执行失败：" + e.getMessage()));
                sendQuietly(emitter, SkillAgentEvent.done("", null));
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // 已经完成，不用再收尾
                }
            } finally {
                tasks.remove(conversationId);
            }
        });
        Future<?> previous = tasks.put(conversationId, task);
        if (previous != null) {
            previous.cancel(true);
        }
        log.info("[SkillAgentService] 会话 {} 发起一轮对话（用户消息 {}）", conversationId, userMessageId);
        return emitter;
    }

    /**
     * 真实的 LLM 工具调用循环（对应 {@code core.rs::run_chat} 的主干）：
     * status → 路由 → 逐项（item_start → 流式补全（reasoning / message_delta）→ 工具调用循环
     * （tool_call / tool_result，每项各自受 maxToolRounds 限制，-1 不限））→ done（带整轮 usage）。
     * 被 stop 置位就退出并发 status（"已停止生成"）。
     */
    private void runChatTurn(Long conversationId, String userMsg, SseEmitter emitter) throws IOException {
        runChatTurn(conversationId, userMsg, emitter, CORE_RUN_CHAT);
    }

    /**
     * 同上，模板由调用方给（单测注入一个带标记的模板，就能在请求里认出"最后一条 user"）。
     *
     * <p>失败语义（对齐 {@code core.rs} 的 {@code run_item_tools(...).await?}）：某一项持久性失败
     * 就整轮中断，由 {@link #startChat} 的兜底 catch 收成 status + done；不把前一项的正文当成
     * 这一项的回复继续往下做。
     */
    void runChatTurn(Long conversationId, String userMsg, SseEmitter emitter, String coreRunChat) throws IOException {
        AgentConversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) {
            send(emitter, SkillAgentEvent.status("会话不存在"));
            send(emitter, SkillAgentEvent.done("", null));
            emitter.complete();
            return;
        }
        SkillAgentConfig config = SkillAgentConfig.load(configs, userSettings);

        send(emitter, SkillAgentEvent.status("思考中…"));

        // 降级路径前置检查：LLM 没配好只说明原因 + done，不空流、不挂住
        if (!llmClient.isUsable()) {
            log.warn("[SkillAgentService] 会话 {} LLM 未配置，走降级路径", conversationId);
            send(emitter, SkillAgentEvent.status("模型没配好：LLM 配置不可用（baseUrl / api-key 缺失）"));
            send(emitter, SkillAgentEvent.done("", null));
            emitter.complete();
            return;
        }

        Path sandboxDir = config.resolveSandboxDir(dataDir);
        Path skillsDir = config.resolveSkillsDir(gameDataDir);
        List<String> existingScriptKeys = scriptRepository.findAll().stream()
                .map(InterviewScript::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        // ① 轮开始前算一次（对齐 core.rs:99-104）：整轮共用一份
        String scriptBlock = ScriptBlock.build(sandboxDir, conversation.getScriptKey(), existingScriptKeys);

        SkillAgentFileTools fileTools = new SkillAgentFileTools(sandboxDir, config.isAllowAnyPath());
        SkillAgentTools tools = new SkillAgentTools(fileTools, skillsDir, scriptRepository,
                chapterRepository, validator, lander);
        SkillAgentTools.RunContext runContext =
                new SkillAgentTools.RunContext(conversation.getScriptKey(), existingScriptKeys);

        // 路由：判断这一轮要干什么，排出有序队列；路由失败只回话（chat），不给写工具。
        // 输入四段齐全（对齐 router.rs:154-194）：剧本现状（key / 设计稿章节 / 已落盘章节）+ 上一轮欠账
        //   + 上两句对话 + 用户这一句。缺了现状与欠账，模型不知道后面该接什么，就永远只排 1 项。
        String routeScriptKey = conversation.getScriptKey();
        Path routeScriptDir = scriptDir(sandboxDir, routeScriptKey);
        // 账本的作用域与物证一致：都以剧本包目录为准（.agent/ 不在沙箱根）
        Path queueDir = scriptDir(sandboxDir, routeScriptKey);
        SkillAgentRouter.RoutePlan plan = router.route(new SkillAgentRouter.RouteInput(
                userMsg,
                recentTurnsText(conversationId),
                null,
                routeScriptKey,
                ScriptFacts.planChapters(routeScriptDir),
                lander.writtenChapterIds(routeScriptKey, routeScriptDir),
                // 队列：上一轮排过、还没做完的（对齐 stage/queue.rs::readQueueProgress → router 的【上一轮排过】段）
                StageQueue.readQueueProgress(queueDir)));
        List<SkillAgentRouter.QueueItem> items = plan.items();
        // 登记时刻：整轮一个，各项共用（对齐 core.rs 的 turn_start / registered_at）
        Instant turnStart = Instant.now();
        StageQueue.writeQueue(queueDir, items, turnStart, lander);
        log.info("[SkillAgentService] 会话 {} 路由：{} 项，任务={}，判据={}",
                conversationId, items.size(),
                items.stream().map(i -> i.kind().key()).toList(),
                plan.reason() == null ? "（无）" : plan.reason());

        // 历史（只回放 user / assistant 的正文；tool 行靠工具调用重新产生，不进上下文）：
        // 各项共用同一份，本项的任务块与 core_run_chat 以尾部两条 user 追加。
        List<SkillAgentLlmClient.Msg> baseHistory = new ArrayList<>();
        for (AgentMessage row : messageRepository.findByConversationIdOrderByIdAsc(conversationId)) {
            if (!"user".equals(row.getRole()) && !"assistant".equals(row.getRole())) {
                continue;
            }
            if (row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            baseHistory.add("assistant".equals(row.getRole())
                    ? SkillAgentLlmClient.Msg.assistant(row.getContent())
                    : SkillAgentLlmClient.Msg.user(row.getContent()));
        }

        int maxRounds = config.getMaxToolRounds() < 0
                ? Integer.MAX_VALUE
                : Math.max(1, config.getMaxToolRounds());
        StringBuilder reply = new StringBuilder();
        UsageAccumulator usageAccumulator = new UsageAccumulator();
        // 二跑后仍没落地的项（第几项，从 1 起）
        List<Integer> missedItems = new ArrayList<>();
        // 本轮要不要产出能跑章节：由计划里有没有 WriteChapter / ReviseChapter 派生（对齐 core.rs:137-140）
        boolean land = items.stream().anyMatch(i -> i.kind().producesRunnableChapters());
        // 我方没有 preplan 的跳过项 → 空表
        List<String> skipped = List.of();
        // 中间项留下的「要最后一项问用户的事」（对齐 core.rs:289-291）
        List<String> deferred = new ArrayList<>();


        // 逐项执行（对齐 core.rs:175-319 的 while 循环）：每项有自己的 system prompt（角色手册 +
        // 工具白名单按项算）和自己的工具轮次，第 1 项用掉的 round 数不挤占后续项。
        for (int idx = 0; idx < items.size(); idx++) {
            if (cancelled.get()) {
                send(emitter, SkillAgentEvent.status("已停止生成"));
                break;
            }
            SkillAgentRouter.TaskKind kind = items.get(idx).kind();
            String target = items.get(idx).target() == null ? "" : items.get(idx).target();
            String head = itemHeader(kind, target, idx, items.size());
            send(emitter, SkillAgentEvent.itemStart(idx + 1, items.size(), head));
            reply.append(head);

            // 本项承接判断（对齐 core.rs:179-197 的 recheck_handoff）：事实按本项目标重算一次
            Path itemScriptDir = scriptDir(sandboxDir, runContext.scriptKey());
            ScriptFacts itemFacts = ScriptFacts.collect(itemScriptDir, target,
                    lander.writtenChapterIds(runContext.scriptKey(), itemScriptDir));
            ScriptFacts.Handoff handoff = ScriptFacts.reconcile(kind, itemFacts);
            // 工具清单要看承接判断（对齐 core.rs:437-448 item_tools：EXPLAIN 时只给只读两项）
            List<String> allowed = SkillAgentRouter.toolsFor(kind, handoff);
            log.info("[SkillAgentService] 会话 {} 第 {}/{} 项：任务={} 目标={} 工具={}",
                    conversationId, idx + 1, items.size(), kind.key(), target, String.join("/", allowed));
            boolean isLast = idx == items.size() - 1;

            // 一条消息都不许少（对齐 core.rs:235-264 的拼装顺序）：
            //   system = base + script_block + （skills_block 空）+ task_block
            String taskBlock = StagePrompt.buildTaskBlock(new StagePrompt.TaskBlockInput(
                    kind, handoff, items, idx, userMsg, skillsDir, land, isLast, skipped, allowed));
            String systemPrompt = buildSystemPrompt(config, allowed, sandboxDir, skillsDir,
                    SkillAgentRouter.roleFor(kind))
                    + scriptBlock + "" /* skills_block 空 */ + taskBlock;
            StageFacts snap = StageFacts.collect(runContext.scriptKey(), itemScriptDir,
                    lander.writtenChapterIds(runContext.scriptKey(), itemScriptDir));
            String runMaterials = StagePrompt.buildRunMaterials(snap, kind, dataDir);
            String digest = StagePrompt.handoffDigest(queueDir, items, idx, deferred);

            List<SkillAgentLlmClient.Msg> messages = new ArrayList<>();
            messages.add(SkillAgentLlmClient.Msg.system(systemPrompt));
            messages.addAll(baseHistory);
            if (!digest.isBlank()) {
                messages.add(SkillAgentLlmClient.Msg.user(digest));
            }
            if (!runMaterials.isEmpty()) {
                messages.add(SkillAgentLlmClient.Msg.user(runMaterials));
            }
            // 本项任务书补在最后一条：模型最后读到的必须是"我这一项是什么"（对齐 core.rs:250-261）
            messages.add(SkillAgentLlmClient.Msg.user(renderCoreRunChat(kind, target, idx, items.size(), coreRunChat)));


            SkillAgentTools.RunContext itemRun = runContext;
            // 【代码核对】的基准：必须在 runItemTools 之前拍（多项时第 1 项的产物会被第 2 项误判）。
            // 剧本包目录用唯一的 scriptDir(...) 推导：.agent/ 永远在 <sandbox>/scripts/<key> 下，不在沙箱根
            String evidenceScriptKey = resolveScriptKey(conversationId);
            Path evidenceScriptDir = scriptDir(sandboxDir, evidenceScriptKey);
            boolean judgeable = ItemEvidence.judgeable(kind, target);
            ItemEvidence.Snapshot before = judgeable
                    ? ItemEvidence.capture(kind, target, evidenceScriptDir, evidenceScriptKey, lander)
                    : null;
            SkillAgentLlmClient.Completion last = runItemTools(conversationId, emitter, messages, tools,
                    itemRun, new SkillAgentTools.ItemScope(kind.producesRunnableChapters(), kind),
                    kind, allowed, maxRounds, usageAccumulator);
            // 被取消且一轮都没跑：这一项没有正文（不能把上一项的文本当成本项的）
            if (last != null && last.text() != null) {
                reply.append(last.text());
                // 中间项写的【留给最后一项问】原样摘给后面各项（对齐 core.rs:289-291）
                String question = StagePrompt.deferredQuestion(last.text());
                if (question != null) {
                    deferred.add(question);
                }
            }
            // 记一次账（对齐 core.rs:284 save_queue）：这一项的工具已经跑过，按盘上事实打勾
            StageQueue.writeQueue(queueDir, items, turnStart, lander);

            // 【代码核对】二跑（对齐 core.rs:286-314）：模型说做完了，代码自己去盘上/库里核；
            // 没看到产物就再喂一条 user 强制做一次，只跑一次、不再递归核对。
            if (judgeable && !cancelled.get()) {
                if (!ItemEvidence.produced(kind, target, before, evidenceScriptDir, evidenceScriptKey, lander)) {
                    log.info("[SkillAgentService] 会话 {} 第 {} 项（{} {}）没在盘上看到产物，发起【代码核对】二跑",
                            conversationId, idx + 1, kind.key(), target);
                    messages.add(SkillAgentLlmClient.Msg.user(coreCheckText(kind, target)));
                    SkillAgentLlmClient.Completion second = runItemTools(conversationId, emitter, messages, tools,
                            itemRun, new SkillAgentTools.ItemScope(kind.producesRunnableChapters(), kind),
                            kind, allowed, maxRounds, usageAccumulator);
                    if (second != null && second.text() != null) {
                        reply.append(second.text());
                    }
                    // 二跑后再核一次账（对齐 core.rs:314 的第二次 save_queue）
                    StageQueue.writeQueue(queueDir, items, turnStart, lander);
                    // 二跑后仍未产出：不中断整轮，记进回复尾部，并在 done 前推一条 status
                    if (!ItemEvidence.produced(kind, target, before, evidenceScriptDir, evidenceScriptKey, lander)) {
                        log.warn("[SkillAgentService] 会话 {} 第 {} 项（{} {}）二跑后仍无产物",
                                conversationId, idx + 1, kind.key(), target);
                        missedItems.add(idx + 1);
                    }
                }
            }
        }
        long promptTokens = usageAccumulator.promptTokens;
        long completionTokens = usageAccumulator.completionTokens;
        long cachedTokens = usageAccumulator.cachedTokens;

        // 没落地的项：记进回复尾部（原版「本轮盘点」的位置），并在 done 前推一条 status 说明
        for (Integer index : missedItems) {
            reply.append("\n\n第 ").append(index).append(" 项没有在磁盘上看到产物，可能没做完。");
            send(emitter, SkillAgentEvent.status("第 " + index + " 项没有在磁盘上看到产物，可能没做完"));
        }

        SkillAgentEvent.Usage usage = promptTokens + completionTokens > 0
                ? new SkillAgentEvent.Usage(promptTokens, completionTokens,
                        promptTokens + completionTokens, cachedTokens)
                : null;
        send(emitter, SkillAgentEvent.done(reply.toString(), usage));
        emitter.complete();
        log.info("[SkillAgentService] 会话 {} 一轮对话结束：{} 项，回复 {} 字",
                conversationId, items.size(), reply.length());
    }

    /**
     * 单项的工具调用循环（从改造前的单轮 for 循环原样抽出）：流式补全 → 有工具调用就执行并回填 →
     * 没有工具调用即本项结束。每项调用一次，轮数各自独立计算。
     *
     * @param usage 本轮 token 用量的累加器（各项共用，done 事件带的是整轮合计）
     * @return 本项最后一次补全（正文取它的 text）；被取消且一轮都没跑时返回 {@code null}
     */
    private SkillAgentLlmClient.Completion runItemTools(Long conversationId,
                                                        SseEmitter emitter,
                                                        List<SkillAgentLlmClient.Msg> messages,
                                                        SkillAgentTools tools,
                                                        SkillAgentTools.RunContext runContext,
                                                        SkillAgentTools.ItemScope itemScope,
                                                        SkillAgentRouter.TaskKind kind,
                                                        List<String> allowed,
                                                        int maxRounds,
                                                        UsageAccumulator usage) throws IOException {
        SkillAgentLlmClient.Completion completion = null;
        // 同一工具同一参数的连续重复计数（本项目补充，原版 core.rs 没有这条熔断）。
        // 原版 max_tool_rounds 默认 -1（不限，config.rs:42），只靠轮数根本停不住：
        // 实测会话 26 里模型为了"证明自己写了"连调 list_files / read_file 138 次。
        String lastSignature = null;
        int repeatCount = 0;
        boolean circuitBroken = false;
        for (int round = 0; round < maxRounds; round++) {
            if (cancelled.get()) {
                send(emitter, SkillAgentEvent.status("已停止生成"));
                break;
            }
            List<SkillAgentLlmClient.ToolDef> defs = tools.toolDefinitions(allowed);
            completion = llmClient.completeStream(
                    messages, defs, itemOptions(kind),
                    content -> sendQuietly(emitter, SkillAgentEvent.messageDelta(content)),
                    content -> sendQuietly(emitter, SkillAgentEvent.reasoning(content)),
                    cancelled);
            usage.add(completion.usage());

            if (completion.toolCalls().isEmpty()) {
                String text = completion.text() == null ? "" : completion.text();
                insertMessage(conversationId, "assistant", text, completion.reasoning(), null, null, null);
                break;
            }

            // 有工具调用：先落 assistant 行（含 tool_calls，deriveScriptKey 反推剧本 key 靠它）
            String toolCallsJson = toolCallsJson(completion.toolCalls());
            insertMessage(conversationId, "assistant", completion.text(), completion.reasoning(),
                    null, null, toolCallsJson, null);
            messages.add(SkillAgentLlmClient.Msg.assistantWithTools(completion.text(), completion.toolCalls()));

            for (SkillAgentLlmClient.ToolCall call : completion.toolCalls()) {
                if (cancelled.get()) {
                    break;
                }
                JsonNode args = parseJson(call.arguments());
                send(emitter, SkillAgentEvent.toolCall(call.id(), call.name(),
                        args == null ? null : objectMapper.convertValue(args, Map.class), call.arguments()));

                // 熔断（本项目补充）：同一工具 + 同一参数连续第 N 次就掐掉这一项，不再喂给模型。
                // 模型陷入「反复读同一个文件找原因」时，轮数上限是 -1 拦不住，只能看重复签名。
                String signature = call.name() + " " + (call.arguments() == null ? "" : call.arguments().strip());
                if (signature.equals(lastSignature)) {
                    repeatCount++;
                } else {
                    lastSignature = signature;
                    repeatCount = 1;
                }
                if (repeatCount >= REPEAT_TOOL_LIMIT) {
                    log.warn("[SkillAgentService] 会话 {} 同一工具同一参数连续重复 {} 次（{}），中断本项工具循环",
                            conversationId, repeatCount, abbreviate(signature, 200));
                    sendQuietly(emitter, SkillAgentEvent.status(
                            "同一个调用连续重复 " + repeatCount + " 次，已中断这一项的工具循环"));
                    circuitBroken = true;
                    break;
                }

                SkillAgentTools.ToolResult result;
                if (call.arguments() != null && !call.arguments().isBlank() && args == null) {
                    // 参数不是合法 JSON（多半被截断），不执行（对齐 core.rs::run_one_tool_call 的 args_invalid 分支）
                    result = SkillAgentTools.ToolResult.failure(
                            "这次调用没有执行：参数不是合法 JSON，多半是内容太长被截断。收到的参数开头：\n"
                                    + abbreviate(call.arguments(), 400));
                } else {
                    result = tools.execute(runContext, itemScope, allowed, call.name(), args);
                }
                send(emitter, SkillAgentEvent.toolResult(call.id(), call.name(), result.ok(),
                        result.output(), result.error()));
                messages.add(SkillAgentLlmClient.Msg.toolResult(call.id(), result.output()));
                insertMessage(conversationId, "tool", result.output(), null, null, null, call.id());

                // 写 story_config.yaml 所在剧本包 → 给会话绑定 key（对齐 tools.rs::bind_script_key_if_new）
                if ("write_file".equals(call.name()) && result.ok()) {
                    bindScriptKeyIfNew(conversationId, args);
                }
            }
            if (circuitBroken) {
                break;
            }
            if (round == maxRounds - 1 && !cancelled.get()) {
                send(emitter, SkillAgentEvent.error("已达到最大工具调用轮数（" + maxRounds + "），已停止"));
            }
        }
        return completion;
    }

    /**
     * 这一项的生成参数：任务级 thinking 优先，设置项兜底（1:1 迁移 {@code config.rs:101-115}
     * {@code resolve_skill_agent_provider} 里的 {@code stage_thinking.or(config.enable_thinking)}）。
     *
     * <p>原版在这里重新 build 一个 LlmClient；本项目只有单一 provider，所以只把 thinking 覆盖到
     * {@link LlmOptions} 上，其余五个参数仍走 {@link LlmOptions#from}。
     */
    private LlmOptions itemOptions(SkillAgentRouter.TaskKind kind) {
        LlmOptions base = LlmOptions.from(configs);
        SkillAgentConfig cfg = SkillAgentConfig.load(configs, userSettings);
        Boolean stageThinking = kind == null ? null : kind.thinking();
        return new LlmOptions(base.model(), base.temperature(), base.topP(), base.maxTokens(),
                base.timeoutSeconds(), stageThinking != null ? stageThinking : cfg.getEnableThinking());
    }

    // ============================================================
    // 一项的标题与「我这一项是什么」（对齐 core.rs::item_header / stage/prompt.rs::render_text_of）
    // ============================================================

    /**
     * 一项的标题（{@code item_start} 的 {@code title}，同时作为回复里的分段头）：
     * {@code 【第 i/N 项】任务标签：目标}，目标为空时只写标签。
     */
    static String itemHeader(SkillAgentRouter.TaskKind kind, String target, int idx, int total) {
        return "【第 " + (idx + 1) + "/" + total + " 项】" + renderTextOf(kind, target);
    }

    /**
     * 一项的正文（{@code 类型标签：目标}），视图与注入的进度行共用；目标为空时只写类型标签
     * （逐字对齐 {@code stage/prompt.rs::render_text_of}）。
     */
    static String renderTextOf(SkillAgentRouter.TaskKind kind, String target) {
        String trimmed = target == null ? "" : target.trim();
        if (trimmed.isEmpty()) {
            return kind.label();
        }
        return kind.label() + "：" + trimmed;
    }

    /**
     * 本项任务书（原版 {@code core.rs:250-261}）：{@code core_run_chat.md} 的三个 {@code {}} 依次填
     * 「类型 + 对象」/ 第几项 / 共几项 / 本项点名的对象。
     *
     * <p><b>它必须是整个 messages 的最后一条</b> —— 模型最后读到的必须是"我这一项是什么"；
     * 只给一份事实陈述，模型读完文件就会反问用户、一个字不写。
     *
     * <p>文件里的 {@code Chapters/<id>.yaml} 是原版路径，按 {@link RoleScope} 那套口径换成
     * 本项目的落点（{@code interview_chapter 表}）。
     */
    String renderCoreRunChat(SkillAgentRouter.TaskKind kind, String target, int idx, int total) {
        return renderCoreRunChat(kind, target, idx, total, CORE_RUN_CHAT);
    }

    /**
     * 同上，模板由调用方给。
     *
     * <p>P0-4 顺带的收尾约束：实测模型写完后会反问「要不要转成章节？」——原版靠队列让模型
     * 知道后面还有项，队列未迁期间先在渲染结果最后压一句「不要问用户」。
     * {@code Chat} / {@code Collect} / {@code Summarize} 不加：它们的产物本来就是向用户提问
     * （见 {@link RoleScope#boundaryNote}）。
     */
    String renderCoreRunChat(SkillAgentRouter.TaskKind kind, String target, int idx, int total, String template) {
        String trimmed = target == null ? "" : target.trim();
        String text = template
                .replace("Chapters/<id>.yaml", "interview_chapter 表里 code=<id> 的那一行")
                .replace("Chapters/", "interview_chapter 表");
        String rendered = fillPlaceholders(text,
                renderTextOf(kind, trimmed),
                String.valueOf(idx + 1),
                String.valueOf(total),
                trimmed.isEmpty() ? "（未指明）" : trimmed);
        // 逐字对齐原版：模板后面不追加任何一句自造的话（曾加过一条「本项到此结束…」的收尾尾巴，已删）
        return rendered;
    }

    /**
     * 【代码核对】那条 user 消息（逐字照抄 {@code core.rs:289-295}，只把 {@code Chapters/<id>.yaml}
     * 换成本项目的落点 {@code interview_chapter 表里 code=<id> 的那一行}，与 {@link RoleScope} 同口径）。
     *
     * <p>原版 Rust 的 {@code \} 续行会把缩进去掉，这里同样用字符串拼接接回来，不引入换行。
     */
    static String coreCheckText(SkillAgentRouter.TaskKind kind, String target) {
        return ("【代码核对】这一项在盘上还没有看到产物（" + renderTextOf(kind, target) + "）。"
                + "**增 / 改类**要看到文件在、或被改过；"
                + "**删除类**要看到它真的不见了。没落地就不算做完 —— 要么现在就真的做（该调的工具要调，"
                + "比如删除要 `delete_file` 返回成功），要么在回执里说清为什么做不到；**不要写「已做完」**。");
    }

    /** 依序替换模板里的位置占位符 {@code {}}（与 Rust {@code format!} 的顺序语义一致）。 */
    private static String fillPlaceholders(String template, String... args) {
        String out = template;
        for (String arg : args) {
            int index = out.indexOf("{}");
            if (index < 0) {
                break;
            }
            out = out.substring(0, index) + arg + out.substring(index + 2);
        }
        return out;
    }

    /** 整轮 token 用量的累加器（各项共用一份，done 事件带整轮合计）。 */
    private static final class UsageAccumulator {
        private long promptTokens;
        private long completionTokens;
        private long cachedTokens;

        void add(SkillAgentLlmClient.Usage usage) {
            if (usage == null) {
                return;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            cachedTokens += usage.cachedTokens();
        }
    }

    /** 上两句纯对话（旧的前）：路由的 recent 输入。 */
    private String recentTurnsText(Long conversationId) {
        List<AgentMessage> rows = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        StringBuilder out = new StringBuilder();
        int turns = 0;
        for (int i = rows.size() - 1; i >= 0 && turns < 4; i--) {
            AgentMessage row = rows.get(i);
            if (!"user".equals(row.getRole()) && !"assistant".equals(row.getRole())) {
                continue;
            }
            if (row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            out.insert(0, ("assistant".equals(row.getRole()) ? "你：" : "用户：")
                    + abbreviate(row.getContent().replace('\n', ' '), 120) + "\n");
            turns++;
        }
        return out.toString();
    }

    /**
     * 拼本轮 system prompt 的 <b>base 段</b>。
     *
     * <p>结构（顺序是硬约束，边界底线必须在最前面 —— 流程描述覆盖不了它）：
     * <ol>
     *   <li>边界底线（代码写死，{@code role_boundary_baseline.md}）</li>
     *   <li>本角色唯一预注入的手册（{@link Role#doc()}，只这一本）</li>
     *   <li>本轮可用工具 / 沙箱路径说明</li>
     * </ol>
     *
     * <p>整段 system 的拼接在调用方（对齐 {@code history.rs:124}）：
     * {@code base + script_block + skills_block + task_block}，本方法只产 base。
     *
     * <p><b>不列出全部技能</b>：原版把"有哪些技能"整个从 prompt 里拿掉了。列出来模型就知道
     * "还有别的路可以走"，会试图去读别的手册，两套规范在同一个请求里打架。
     */
    private String buildSystemPrompt(SkillAgentConfig config, List<String> allowed,
                                     Path sandboxDir, Path skillsDir, Role role) {
        StringBuilder sb = new StringBuilder();

        // 1. 边界底线：代码写死，流程 Agent 不得覆盖
        sb.append(BOUNDARY_BASELINE.strip()).append("\n\n");

        // 2. 本角色手册：只注入自己那本
        if (role != null) {
            String doc = readSkillDoc(skillsDir, role.doc());
            if (doc != null && !doc.isBlank()) {
                sb.append(doc.strip()).append("\n\n");
            } else {
                log.warn("[SkillAgentService] 角色 {} 的手册缺失：{}", role.label(), role.doc());
            }
            sb.append("本轮你扮演的是「").append(role.label()).append("」。\n");
        }

        // 3. 运行环境
        sb.append("本轮可用工具：").append(String.join(" / ", allowed)).append("。\n");
        sb.append("文件工具的沙箱根目录是 ").append(sandboxDir)
                .append("，相对路径都相对它解析；不要访问沙箱之外的路径。\n");
        sb.append("技能库存放在 ").append(skillsDir).append("。\n");
        return sb.toString();
    }

    /** 读某个角色手册的正文；文件不存在返回 null（不抛，缺手册不该让整轮崩掉）。 */
    private String readSkillDoc(Path skillsDir, String relative) {
        Path file = skillsDir.resolve(relative).normalize();
        if (!file.startsWith(skillsDir.normalize())) {
            log.warn("[SkillAgentService] 手册路径越界，已拒绝：{}", relative);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SkillAgentService] 读手册失败 {}：{}", file, e.toString());
            return null;
        }
    }

    /** 工具调用列表 → {@code tool_calls} 列的 JSON（deriveScriptKey 反推剧本 key 靠这个列）。 */
    private String toolCallsJson(List<SkillAgentLlmClient.ToolCall> calls) {
        ArrayNode array = objectMapper.createArrayNode();
        for (SkillAgentLlmClient.ToolCall call : calls) {
            ObjectNode fn = array.addObject().putObject("function");
            fn.put("name", call.name());
            fn.put("arguments", call.arguments());
            array.add(objectMapper.createObjectNode()
                    .put("id", call.id())
                    .put("type", "function")
                    .set("function", fn));
        }
        return array.toString();
    }

    /**
     * {@code /scripts/<key>/…} 的写入 → 绑定会话剧本 key（对齐 tools.rs::bind_script_key_if_new）。
     *
     * <p>每项开跑前都从库里重读，不靠手里那个 {@link AgentConversation} 对象的 {@code scriptKey}：
     * 第 1 项绑上之后，第 2 项手里的对象还是旧值，会把绑定覆盖掉。
     */
    private void bindScriptKeyIfNew(Long conversationId, JsonNode args) {
        AgentConversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null || conversation.getScriptKey() != null) {
            return;
        }
        String path = SkillAgentTools.textArg(args, "path");
        int index = path.indexOf("/scripts/");
        if (index < 0) {
            return;
        }
        String rest = path.substring(index + "/scripts/".length());
        int slash = rest.indexOf('/');
        String key = slash < 0 ? rest : rest.substring(0, slash);
        if (key.isBlank() || scriptRepository.findByCode(key).isEmpty()) {
            return;
        }
        conversation.setScriptKey(key);
        conversationRepository.save(conversation);
        log.info("[SkillAgentService] 会话 {} 已绑定剧本 key: {}", conversationId, key);
    }

    /** SSE 写一条事件；连接已断时吞掉异常（流式回调里不能让它往上冒）。 */
    private void sendQuietly(SseEmitter emitter, SkillAgentEvent event) {
        try {
            send(emitter, event);
        } catch (IOException | IllegalStateException e) {
            log.debug("[SkillAgentService] SSE 写入失败（连接可能已断开）：{}", e.toString());
        }
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 停止这一轮：置全局取消标志 + abort 该会话的任务句柄。 */
    public void stopChat(Long conversationId) {
        cancelled.set(true);
        Future<?> task = tasks.remove(conversationId);
        if (task != null) {
            task.cancel(true);
        }
        log.info("[SkillAgentService] 会话 {} 停止生成", conversationId);
    }

    /** 回溯：删掉 id &gt;= messageId 的消息，把对话退回到那条消息之前。 */
    @Transactional
    public void rewind(Long conversationId, Long messageId) {
        requireConversation(conversationId);
        if (messageId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId 不能为空");
        }
        messageRepository.deleteByConversationIdAndIdGreaterThanEqual(conversationId, messageId);
        touchConversation(conversationId);
        log.info("[SkillAgentService] 会话 {} 回溯到消息 {} 之前", conversationId, messageId);
    }

    /**
     * 决断一条审批（对齐 {@code editor_agent_resolve_approval}）：待决表里没有这条请求时静默忽略
     * （原版同款：{@code approvals.remove} 拿不到就不做事）。
     */
    public void resolveApproval(String requestId, boolean allowed) {
        CompletableFuture<Boolean> pending = approvals.remove(requestId);
        if (pending != null) {
            pending.complete(allowed);
        }
        log.info("[SkillAgentService] 审批 {} → {}", requestId, allowed);
    }

    /**
     * 登记一条待审批请求（E7a2 的工具执行器用）：返回前端决断后才会完成的 future。
     *
     * <p>本批骨架没有工具调用，所以只有 {@link #resolveApproval} 会消费它。
     */
    public CompletableFuture<Boolean> registerApproval(String requestId) {
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        approvals.put(requestId, pending);
        return pending;
    }

    @PreDestroy
    public void shutdown() {
        chatExecutor.shutdownNow();
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private AgentConversation requireConversation(Long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在：" + conversationId));
    }

    /** 刷新 updated_at，保持会话列表排序（对齐 {@code db.rs::touch_conversation}）。 */
    private void touchConversation(Long conversationId) {
        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conversation);
        });
    }

    private AgentMessage insertMessage(Long conversationId, String role, String content, String reasoning,
                                       Long promptTokens, Long completionTokens, String toolCallId) {
        return insertMessage(conversationId, role, content, reasoning,
                promptTokens, completionTokens, null, toolCallId);
    }

    /**
     * 同上，额外带 {@code tool_calls}。
     *
     * <p><b>两列不能混用</b>（对齐 OpenAI 消息格式 + {@code schema-h2.sql:236-237}）：
     * {@code tool_calls} 是 assistant 行发出的调用列表（CLOB），
     * {@code tool_call_id} 是 tool 行回填的单个调用 id（VARCHAR(64)）。
     * 早前版本把调用列表的 JSON 塞进 {@code tool_call_id}，只要模型一发起工具调用就
     * {@code Value too long for column "tool_call_id VARCHAR(64)"}，整轮对话只剩用户那句。
     */
    private AgentMessage insertMessage(Long conversationId, String role, String content, String reasoning,
                                       Long promptTokens, Long completionTokens,
                                       String toolCalls, String toolCallId) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        // 空 content 存 NULL（对齐 db.rs:131）
        message.setContent(content == null || content.isEmpty() ? null : content);
        message.setReasoning(reasoning);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setToolCalls(toolCalls);
        message.setToolCallId(toolCallId);
        message.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(message);
    }

    /** 从历史消息的工具调用参数里反推剧本 key；推不出来返回 null。 */
    private String deriveScriptKey(Long conversationId) {
        List<String> known = scriptRepository.findAll().stream()
                .map(InterviewScript::getCode)
                .toList();
        if (known.isEmpty()) {
            return null;
        }
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (AgentMessage message : messageRepository.findByConversationIdOrderByIdAsc(conversationId)) {
            String raw = message.getToolCalls();
            if (raw == null || !raw.contains(SCRIPT_PATH_MARKER)) {
                continue;
            }
            JsonNode calls = parseJson(raw);
            if (calls == null || !calls.isArray()) {
                continue;
            }
            for (JsonNode call : calls) {
                JsonNode function = call.get("function");
                if (function == null || !"write_file".equals(function.path("name").asText())) {
                    continue;
                }
                JsonNode args = parseJson(function.path("arguments").asText(""));
                if (args == null) {
                    continue;
                }
                String key = scriptKeyOf(args.path("path").asText(""), known);
                if (key != null) {
                    votes.merge(key, 1, Integer::sum);
                }
            }
        }
        String best = null;
        int bestVotes = 0;
        for (Map.Entry<String, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestVotes) {
                best = vote.getKey();
                bestVotes = vote.getValue();
            }
        }
        return best;
    }

    /** {@code /scripts/<key>/…} → key；不在已知剧本里返回 null。 */
    private static String scriptKeyOf(String path, List<String> known) {
        int index = path.indexOf(SCRIPT_PATH_MARKER);
        if (index < 0) {
            return null;
        }
        String rest = path.substring(index + SCRIPT_PATH_MARKER.length());
        int slash = rest.indexOf('/');
        String candidate = slash < 0 ? rest : rest.substring(0, slash);
        return known.contains(candidate) ? candidate : null;
    }

    private ConversationInfo toInfo(AgentConversation conversation) {
        return new ConversationInfo(conversation.getId(), conversation.getTitle(), conversation.getScriptKey(),
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }

    private PersistedMessage toMessage(AgentMessage message) {
        return new PersistedMessage(message.getId(), message.getRole(), message.getContent(),
                message.getReasoning(), message.getPromptTokens(), message.getCompletionTokens(),
                message.getCachedTokens(), parseJson(message.getToolCalls()), message.getToolCallId(),
                message.getCreatedAt());
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            log.warn("[SkillAgentService] JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private void send(SseEmitter emitter, SkillAgentEvent event) throws IOException {
        emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 解析一个可能以相对路径给出的目录：先按字面量找，找不到再往上翻一级
     * （与 {@code CharacterCardService#resolveRoot} 同款处理：jar 与单测都在 backend/ 下启动）。
     */
    private static Path resolveExistingDir(String dir) {
        Path direct = Path.of(dir).toAbsolutePath().normalize();
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromParent = Path.of("..").resolve(dir).toAbsolutePath().normalize();
        return Files.isDirectory(fromParent) ? fromParent : direct;
    }
}

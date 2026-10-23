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

    /** SSE 连接的兜底超时，与 {@code ScriptPlayController} 同规格。 */
    private static final long SSE_TIMEOUT_MILLIS = 180_000L;

    /** 技能 SKILL.md 的大小上限（对齐 {@code skills.rs::MAX_SKILL_BYTES}）。 */
    private static final long MAX_SKILL_BYTES = 512 * 1024L;

    /** 单份流程产物的大小上限（对齐 {@code agent.rs::ARTIFACT_MAX_BYTES}）：超了就不列内容。 */
    private static final long ARTIFACT_MAX_BYTES = 64 * 1024L;

    /** 原版 {@code skills.rs::MAX_SKILLS}。 */
    private static final int MAX_SKILLS = 200;

    /** 反推剧本 key 用的路径标记（原版 {@code db.rs} 的 {@code like("%/scripts/%")}）。 */
    private static final String SCRIPT_PATH_MARKER = "/scripts/";

    /**
     * 边界底线（对齐 {@code core.rs::BOUNDARY_BASELINE = include_str!("prompts/role_boundary_baseline.md")}）。
     *
     * <p>四个角色共用同一份，且必须在 system prompt <b>最前面</b>：它是"没让你做的别做"这一条
     * 硬约束，任何角色的流程描述都不能覆盖它。用 {@link Class#getResourceAsStream} 读，
     * 因为它要跟着 jar 走（资源目录里的 .md 会被打进 jar，用 {@link Path} 读不到）。
     */
    private static final String BOUNDARY_BASELINE = loadClasspathText("/agent/prompts/role_boundary_baseline.md");

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
        this.router = new SkillAgentRouter(llmClient, configs, objectMapper);
        // app.characters-dir 指向 game_data/characters，往上两级即项目根（原版 data_dir）；
        // 与 CharacterCardService 同样处理「jar 在 backend/ 下、资产在项目根」的相对路径问题。
        Path characters = resolveExistingDir(charactersDir);
        this.gameDataDir = characters.getParent();
        this.dataDir = this.gameDataDir == null ? Path.of("").toAbsolutePath() : this.gameDataDir.getParent();
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
        Path agentDir = gameDataDir.resolve("scripts").resolve(scriptKey).resolve(".agent");
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
                // 兜底降级：任何没接住的异常也要收成 status + done，不许空流 / 挂住 / 500
                try {
                    send(emitter, SkillAgentEvent.status("对话执行失败：" + e.getMessage()));
                    send(emitter, SkillAgentEvent.done("", null));
                } catch (IOException ignored) {
                    // 连接已断，无需再收尾
                }
                emitter.complete();
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
     * 真实的 LLM 工具调用循环（对应 {@code core.rs::run_chat} 的单轮主干）：
     * status → 路由 → 流式补全（reasoning / message_delta）→ 工具调用循环（tool_call / tool_result，
     * 受 maxToolRounds 限制，-1 不限）→ done（带 usage）。被 stop 置位就退出并发 status（"已停止生成"）。
     */
    private void runChatTurn(Long conversationId, String userMsg, SseEmitter emitter) throws IOException {
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
        SkillAgentFileTools fileTools = new SkillAgentFileTools(sandboxDir, config.isAllowAnyPath());
        SkillAgentTools tools = new SkillAgentTools(fileTools, skillsDir, scriptRepository,
                chapterRepository, validator);
        SkillAgentTools.RunContext runContext =
                new SkillAgentTools.RunContext(conversation.getScriptKey(), existingScriptKeys);

        // 路由：判断这一轮要干什么，决定工具白名单；路由失败只回话（chat），不给写工具
        SkillAgentRouter.RoutePlan plan = router.route(
                new SkillAgentRouter.RouteInput(userMsg, recentTurnsText(conversationId), null));
        SkillAgentRouter.TaskKind kind = plan.items().get(0).kind();
        List<String> allowed = SkillAgentRouter.toolsFor(kind);
        log.info("[SkillAgentService] 会话 {} 路由：{} 项，首轮任务={}，判据={}，工具={}",
                conversationId, plan.items().size(), kind.key(),
                plan.reason() == null ? "（无）" : plan.reason(), String.join("/", allowed));

        // 消息表：system + 历史 + 本轮任务书
        List<SkillAgentLlmClient.Msg> messages = new ArrayList<>();
        messages.add(SkillAgentLlmClient.Msg.system(
                buildSystemPrompt(config, allowed, sandboxDir, skillsDir, SkillAgentRouter.roleFor(kind))));
        for (AgentMessage row : messageRepository.findByConversationIdOrderByIdAsc(conversationId)) {
            // 只回放 user / assistant 的正文；tool 行靠工具调用重新产生，不进上下文
            if (!"user".equals(row.getRole()) && !"assistant".equals(row.getRole())) {
                continue;
            }
            if (row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            messages.add("assistant".equals(row.getRole())
                    ? SkillAgentLlmClient.Msg.assistant(row.getContent())
                    : SkillAgentLlmClient.Msg.user(row.getContent()));
        }
        messages.add(SkillAgentLlmClient.Msg.user("""
                【本轮任务】（第 1/1 项 · %s）
                用户这一句：%s""".formatted(kind.label(), userMsg)));

        int maxRounds = config.getMaxToolRounds() < 0
                ? Integer.MAX_VALUE
                : Math.max(1, config.getMaxToolRounds());
        long promptTokens = 0;
        long completionTokens = 0;
        long cachedTokens = 0;
        StringBuilder reply = new StringBuilder();

        for (int round = 0; round < maxRounds; round++) {
            if (cancelled.get()) {
                send(emitter, SkillAgentEvent.status("已停止生成"));
                break;
            }
            List<SkillAgentLlmClient.ToolDef> defs = tools.toolDefinitions(allowed);
            SkillAgentLlmClient.Completion completion = llmClient.completeStream(
                    messages, defs, LlmOptions.from(configs),
                    content -> sendQuietly(emitter, SkillAgentEvent.messageDelta(content)),
                    content -> sendQuietly(emitter, SkillAgentEvent.reasoning(content)),
                    cancelled);
            if (completion.usage() != null) {
                promptTokens += completion.usage().promptTokens();
                completionTokens += completion.usage().completionTokens();
                cachedTokens += completion.usage().cachedTokens();
            }

            if (completion.toolCalls().isEmpty()) {
                String text = completion.text() == null ? "" : completion.text();
                insertMessage(conversationId, "assistant", text, completion.reasoning(),
                        promptTokens, completionTokens, null);
                reply.append(text);
                break;
            }

            // 有工具调用：先落 assistant 行（含 tool_calls，deriveScriptKey 反推剧本 key 靠它）
            String toolCallsJson = toolCallsJson(completion.toolCalls());
            insertMessage(conversationId, "assistant", completion.text(), completion.reasoning(),
                    promptTokens, completionTokens, toolCallsJson);
            messages.add(SkillAgentLlmClient.Msg.assistantWithTools(completion.text(), completion.toolCalls()));

            for (SkillAgentLlmClient.ToolCall call : completion.toolCalls()) {
                if (cancelled.get()) {
                    break;
                }
                JsonNode args = parseJson(call.arguments());
                send(emitter, SkillAgentEvent.toolCall(call.id(), call.name(),
                        args == null ? null : objectMapper.convertValue(args, Map.class), call.arguments()));

                SkillAgentTools.ToolResult result;
                if (call.arguments() != null && !call.arguments().isBlank() && args == null) {
                    // 参数不是合法 JSON（多半被截断），不执行（对齐 core.rs::run_one_tool_call 的 args_invalid 分支）
                    result = SkillAgentTools.ToolResult.failure(
                            "这次调用没有执行：参数不是合法 JSON，多半是内容太长被截断。收到的参数开头：\n"
                                    + abbreviate(call.arguments(), 400));
                } else {
                    result = tools.execute(runContext,
                            new SkillAgentTools.ItemScope(kind.producesRunnableChapters(), kind),
                            allowed, call.name(), args);
                }
                send(emitter, SkillAgentEvent.toolResult(call.id(), call.name(), result.ok(),
                        result.output(), result.error()));
                messages.add(SkillAgentLlmClient.Msg.toolResult(call.id(), result.output()));
                insertMessage(conversationId, "tool", result.output(), null, null, null, call.id());

                // 写 story_config.yaml 所在剧本包 → 给会话绑定 key（对齐 tools.rs::bind_script_key_if_new）
                if ("write_file".equals(call.name()) && result.ok() && conversation.getScriptKey() == null) {
                    bindScriptKeyIfNew(conversationId, conversation, args);
                }
            }
            if (round == maxRounds - 1 && !cancelled.get()) {
                send(emitter, SkillAgentEvent.error("已达到最大工具调用轮数（" + maxRounds + "），已停止"));
            }
        }

        SkillAgentEvent.Usage usage = promptTokens + completionTokens > 0
                ? new SkillAgentEvent.Usage(promptTokens, completionTokens,
                        promptTokens + completionTokens, cachedTokens)
                : null;
        send(emitter, SkillAgentEvent.done(reply.toString(), usage));
        emitter.complete();
        log.info("[SkillAgentService] 会话 {} 一轮对话结束：回复 {} 字", conversationId, reply.length());
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

    /** 系统提示：用户自定义优先，补本轮工具白名单、沙箱位置与技能清单。 */
    /**
     * 拼本轮 system prompt。
     *
     * <p>结构（顺序是硬约束，边界底线必须在最前面 —— 流程描述覆盖不了它）：
     * <ol>
     *   <li>边界底线（代码写死，{@code role_boundary_baseline.md}）</li>
     *   <li>本角色唯一预注入的手册（{@link Role#doc()}，只这一本）</li>
     *   <li>本轮可用工具 / 沙箱路径说明</li>
     * </ol>
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

    /** {@code /scripts/<key>/…} 的写入 → 绑定会话剧本 key（对齐 tools.rs::bind_script_key_if_new）。 */
    private void bindScriptKeyIfNew(Long conversationId, AgentConversation conversation, JsonNode args) {
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
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        // 空 content 存 NULL（对齐 db.rs:131）
        message.setContent(content == null || content.isEmpty() ? null : content);
        message.setReasoning(reasoning);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
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

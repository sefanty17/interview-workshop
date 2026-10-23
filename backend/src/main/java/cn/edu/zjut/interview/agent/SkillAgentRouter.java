package cn.edu.zjut.interview.agent;

import cn.edu.zjut.interview.config.ConfigKey;
import cn.edu.zjut.interview.config.ConfigService;
import cn.edu.zjut.interview.llm.LlmClient;
import cn.edu.zjut.interview.llm.LlmOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 流程 Agent（路由）：判断这一轮到底要干什么；候选枚举进提示词，决策是一次工具调用
 * （{@code submit_plan}，不解析自由文本）。逻辑骨架 1:1 迁移自
 * {@code crates/ling-chat-main/src/ai_service/skill_agent/router.rs}；
 * Rust 的 TaskKind 类型签名不照抄，落成本项目自己的枚举。
 *
 * <p>失败只回话：技能总纲读不到 / LLM 没配好 / 两次尝试都没有可用的 submit_plan，
 * 一律回落 {@link RoutePlan#chat}——不给写工具，就乱改不了文件。
 */
public class SkillAgentRouter {

    private static final Logger log = LoggerFactory.getLogger(SkillAgentRouter.class);

    /** 判据（reason）的长度口径（对齐 {@code router.rs::REASON_MAX_CHARS}）。 */
    private static final int REASON_MAX_CHARS = 60;

    /** 队列上限：一根保险丝，防「模型一次排几十项」（对齐 {@code router.rs::MAX_QUEUE}）。 */
    private static final int MAX_QUEUE = 100;

    /** 路由提示词模板（resources/agent/prompts/router_build_router_system.md）。 */
    private static final String ROUTER_SYSTEM_TEMPLATE = loadTemplate();

    private final SkillAgentLlmClient llm;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public SkillAgentRouter(SkillAgentLlmClient llm, ConfigService configService, ObjectMapper objectMapper) {
        this.llm = llm;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    // ───────────────────────── 任务类型（对应 role.rs::TaskKind 的路由所需子集） ─────────────────────────

    /**
     * 本项目落地的任务类型。原版 {@code role.rs::TaskKind} 是 1:1 迁移对象，但 role.rs
     * 不在本批蓝本清单里，这里只落路由与工具白名单需要的部分（key 与原版一致）。
     */
    public enum TaskKind {
        CHAT("chat", "闲聊"),
        SUMMARIZE("summarize", "整理需求"),
        COLLECT("collect", "收集信息"),
        CHECK_ASSETS("check_assets", "素材盘点"),
        POLISH("polish", "只读体检"),
        OUTLINE("outline", "编写大纲"),
        REVISE_OUTLINE("revise_outline", "改大纲"),
        DRAFT_CHAPTER("draft_chapter", "写正文"),
        WRITE_CHAPTER("write_chapter", "转成 YAML"),
        REVISE_CHAPTER("revise_chapter", "改章节 YAML");

        /** 全部类型，供提示词枚举（顺序即提示词里的顺序）。 */
        public static final List<TaskKind> ALL = List.of(values());

        private final String key;
        private final String label;

        TaskKind(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        /** 按 key 解析；认不出返回 null（原版 {@code TaskKind::parse}，任一处不合法就整体作废）。 */
        public static TaskKind parse(String key) {
            for (TaskKind kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }

        /** 产物是能跑的章节 YAML（原版 {@code produces_runnable_chapters}）。 */
        public boolean producesRunnableChapters() {
            return this == WRITE_CHAPTER || this == REVISE_CHAPTER;
        }

        /** 产物落在 {@code .agent/} 下的稿子里（原版 {@code drafts_into_agent_dir}）。 */
        public boolean draftsIntoAgentDir() {
            return this == OUTLINE || this == REVISE_OUTLINE || this == DRAFT_CHAPTER;
        }
    }

    // ───────────────────────── 工具白名单 ─────────────────────────

    /**
     * 任务类型 → 角色。
     *
     * <p>两层结构：{@code TaskKind} 回答"用户想干什么"，{@link Role} 回答"这件事该谁去做"。
     * 不推翻原有 {@code TaskKind}，只多一次查表，已有路由逻辑不动。
     */
    public static Role roleFor(TaskKind kind) {
        return switch (kind) {
            case CHAT, SUMMARIZE, COLLECT, CHECK_ASSETS -> Role.DEMANDER;
            case OUTLINE, REVISE_OUTLINE, DRAFT_CHAPTER -> Role.WRITER;
            case WRITE_CHAPTER, REVISE_CHAPTER -> Role.TRANSFORMER;
            case POLISH -> Role.OPTIMIZER;
        };
    }

    /**
     * 权限按角色给：角色的产物是什么，就只给写什么的那套工具。
     *
     * <p><b>四个角色一律不含 {@code read_skill}</b>——原版刻意关掉的口子，开了模型就会
     * 在写剧本时跑去查别的角色手册，两套规范在同一个请求里打架。
     */
    public static List<String> toolsFor(TaskKind kind) {
        return roleFor(kind).tools();
    }

    // ───────────────────────── 计划 ─────────────────────────

    /** 队列里的一项。 */
    public record QueueItem(TaskKind kind, String target) {
    }

    /**
     * 路由计划：有序队列（第一项就是本轮要做的，永不为空）+ 一句判据。
     */
    public record RoutePlan(List<QueueItem> items, String boundary, String reason) {

        static RoutePlan single(TaskKind kind, String target) {
            return new RoutePlan(List.of(new QueueItem(kind, target)), null, null);
        }

        public static RoutePlan chat() {
            return single(TaskKind.CHAT, "");
        }

        /** 「只回话」+ 一句为什么，是路由失败时唯一的去处。 */
        static RoutePlan chatDueTo(String why) {
            String reason = why.length() <= REASON_MAX_CHARS ? why : why.substring(0, REASON_MAX_CHARS);
            return new RoutePlan(List.of(new QueueItem(TaskKind.CHAT, "")), null, reason);
        }
    }

    /** 路由输入（对齐 {@code router.rs::RouteInput}，只留本项目落点）。 */
    public record RouteInput(String userMsg, String recentTurns, String hubDoc) {
    }

    // ───────────────────────── route ─────────────────────────

    /**
     * 判断这一轮要干什么：一次 {@code submit_plan} 工具调用，失败重试一次，再失败只回话。
     */
    public RoutePlan route(RouteInput input) {
        if (input.userMsg() == null || input.userMsg().isBlank()) {
            return RoutePlan.chat();
        }
        if (!llm.isUsable()) {
            log.warn("[SkillAgentRouter] LLM 配置不可用，这一轮只回话");
            return RoutePlan.chatDueTo("模型没配好：LLM 配置不可用");
        }

        List<SkillAgentLlmClient.Msg> messages = List.of(
                SkillAgentLlmClient.Msg.system(buildRouterSystem(input.hubDoc())),
                SkillAgentLlmClient.Msg.user(buildRouterUser(input)));
        List<SkillAgentLlmClient.ToolDef> tools = List.of(submitPlanTool());

        String lastErr = "路由失败";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                SkillAgentLlmClient.Completion completion = llm.completeStream(
                        messages, tools, LlmOptions.from(configService), null, null,
                        new java.util.concurrent.atomic.AtomicBoolean(false));
                if (!completion.toolCalls().isEmpty()) {
                    RoutePlan plan = parsePlan(completion.toolCalls().get(0).arguments());
                    if (plan != null) {
                        return plan;
                    }
                }
                log.warn("[SkillAgentRouter] 第 {} 次没有可用的 submit_plan", attempt);
                lastErr = "没给出可用的计划";
            } catch (LlmClient.LlmException e) {
                log.warn("[SkillAgentRouter] 第 {} 次调用失败: {}", attempt, e.getMessage());
                lastErr = "路由调用失败：" + e.getMessage();
            }
        }
        log.warn("[SkillAgentRouter] 重试后仍失败，这一轮只回话: {}", lastErr);
        return RoutePlan.chatDueTo(lastErr);
    }

    // ───────────────────────── 提示词 ─────────────────────────

    /**
     * 稳定部分：角色 + 流程总纲 + 判断规则（对齐 {@code router.rs::build_router_system}）。
     * 模板 4 个占位：任务类型清单 / 两个动作词 / 落地规则 / 流程总纲。
     */
    String buildRouterSystem(String hubDoc) {
        StringBuilder kinds = new StringBuilder();
        for (TaskKind kind : TaskKind.ALL) {
            if (kinds.length() > 0) {
                kinds.append('\n');
            }
            kinds.append("- `").append(kind.key()).append("` = ").append(kind.label());
        }
        return fillTemplate(ROUTER_SYSTEM_TEMPLATE,
                kinds.toString(),
                ACTION_VOCAB,
                ROUTING_LAND_RULES,
                hubDoc == null ? "" : hubDoc);
    }

    /** 【两个动作词】（原版 {@code role::ACTION_VOCAB}；role.rs 不在本批蓝本内，用等价精简文案）。 */
    private static final String ACTION_VOCAB = """
            【两个动作词】要「内容/稿子」（大纲、剧情、正文）是编剧的活，产物落在 `.agent/` 下的稿子里；
            要「转成 YAML / 落盘 / 生成章节 / 写进 Chapters / 存成 yaml」是落盘的活，产物是能跑的
            `Chapters/<id>.yaml` 与 `story_config.yaml`。两者是两件事，别混。""";

    /** 【落地规则】（原版 {@code role::ROUTING_LAND_RULES}；同上，用等价精简文案）。 */
    private static final String ROUTING_LAND_RULES = """
            【落地规则】只有 `write_chapter` / `revise_chapter` 拿得到写文件的完整工具；
            其余类型要么只读，要么只能写 `.agent/` 下的稿子。所以「这一轮要不要产出能跑的章节」
            必须判断清楚：用户没明说要落盘，就排 `outline` / `draft_chapter`。""";

    private String buildRouterUser(RouteInput input) {
        StringBuilder out = new StringBuilder("【剧本现状】\n");
        out.append("剧本：还没绑定（用户可能想新建一个）\n");
        if (input.recentTurns() != null && !input.recentTurns().isBlank()) {
            out.append("\n【上两句对话】\n").append(input.recentTurns().strip()).append('\n');
        }
        out.append("\n【用户这一句】\n").append(input.userMsg().trim());
        return out.toString();
    }

    /** 路由的提交工具（对齐 {@code router.rs::submit_plan_tool}）：kind 用 enum 约束取值。 */
    SkillAgentLlmClient.ToolDef submitPlanTool() {
        StringBuilder enumList = new StringBuilder();
        for (TaskKind kind : TaskKind.ALL) {
            if (enumList.length() > 0) {
                enumList.append(" / ");
            }
            enumList.append(kind.key());
        }
        Map<String, Object> kindProp = Map.of(
                "type", "string",
                "enum", TaskKind.ALL.stream().map(TaskKind::key).toList(),
                "description", "任务类型，只能取：" + enumList);
        Map<String, Object> targetProp = Map.of(
                "type", "string",
                "description", "作用对象：章节 id 或章号列表（如 03 / 02,03）。"
                        + "没有具体对象（素材盘点、校验、仅对话）就留空");
        Map<String, Object> taskItem = Map.of(
                "type", "object",
                "properties", Map.of("kind", kindProp, "target", targetProp),
                "required", List.of("kind"));
        Map<String, Object> tasksProp = Map.of(
                "type", "array",
                "description", "有序任务队列，第一项是本轮要做的。至少一项，最多 " + MAX_QUEUE + " 项。",
                "items", taskItem);
        Map<String, Object> reasonProp = Map.of(
                "type", "string",
                "description", "这么判的判据，一句话（≤" + REASON_MAX_CHARS + " 字）");
        return new SkillAgentLlmClient.ToolDef(
                "submit_plan",
                "提交这一轮的判断：本轮要做的任务，以及这段对话后续还要做的事（有序）。",
                SkillAgentLlmClient.ToolDef.schema(
                        new TreeMap<>(Map.of("tasks", tasksProp, "reason", reasonProp)),
                        "tasks"));
    }

    // ───────────────────────── 解析（对齐 router.rs::parse_plan） ─────────────────────────

    /**
     * 解析并校验 {@code submit_plan}：任一处不合法就整体作废（返回 null），
     * 后果是重试一次、再失败只回话，不猜。
     */
    RoutePlan parsePlan(String arguments) {
        JsonNode args;
        try {
            args = objectMapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (IOException e) {
            return null;
        }
        JsonNode raw = args.path("tasks");
        if (!raw.isArray() || raw.isEmpty()) {
            return null;
        }
        List<QueueItem> items = new ArrayList<>();
        if (raw.size() > MAX_QUEUE) {
            log.warn("[SkillAgentRouter] submit_plan 给了 {} 项，超过上限 {}，丢掉后 {} 项",
                    raw.size(), MAX_QUEUE, raw.size() - MAX_QUEUE);
        }
        for (JsonNode item : raw) {
            if (items.size() >= MAX_QUEUE) {
                break;
            }
            JsonNode kindNode = item.get("kind");
            if (kindNode == null || !kindNode.isTextual()) {
                return null;
            }
            TaskKind kind = TaskKind.parse(kindNode.asText());
            if (kind == null) {
                return null;
            }
            JsonNode targetNode = item.get("target");
            String target = targetNode != null && targetNode.isTextual() ? targetNode.asText().trim() : "";
            if (items.stream().anyMatch(i -> i.kind() == kind && i.target().equals(target))) {
                continue;
            }
            items.add(new QueueItem(kind, target));
        }
        if (items.isEmpty()) {
            return null;
        }
        JsonNode reasonNode = args.get("reason");
        String reason = reasonNode != null && reasonNode.isTextual() ? reasonNode.asText().trim() : "";
        if (reason.isEmpty() || reason.length() > REASON_MAX_CHARS) {
            reason = null;
        }
        return new RoutePlan(items, null, reason);
    }

    // ───────────────────────── 小工具 ─────────────────────────

    /** 依序替换模板里的位置占位符 {@code {}}（Rust format! 的顺序语义）。 */
    private static String fillTemplate(String template, String... args) {
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

    private static String loadTemplate() {
        try (InputStream in = SkillAgentRouter.class.getResourceAsStream(
                "/agent/prompts/router_build_router_system.md")) {
            if (in == null) {
                log.warn("[SkillAgentRouter] 路由提示词模板缺失，路由将只回话");
                return "{}";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[SkillAgentRouter] 路由提示词模板读取失败：{}", e.getMessage());
            return "{}";
        }
    }
}

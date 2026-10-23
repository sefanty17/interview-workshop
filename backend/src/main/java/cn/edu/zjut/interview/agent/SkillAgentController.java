package cn.edu.zjut.interview.agent;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 剧本编辑器 · AI 助手（Skill Agent）接口（规格 E7a1 §2 的端点映射表）。
 *
 * <p>17 个端点一一对应原版 {@code commands.rs:176-192} 注册的 17 个 {@code editor_agent_*} 命令：
 * <pre>
 * GET    /api/editor/agent/settings                        editor_agent_get_settings
 * PUT    /api/editor/agent/settings                        editor_agent_save_settings
 * GET    /api/editor/agent/default-dirs                    editor_agent_get_default_dirs
 * GET    /api/editor/agent/skills                          editor_agent_list_skills
 * GET    /api/editor/agent/skills/{name}                   editor_agent_read_skill
 * POST   /api/editor/agent/conversations                   editor_agent_create_conversation
 * GET    /api/editor/agent/conversations                   editor_agent_list_conversations
 * DELETE /api/editor/agent/conversations/{id}              editor_agent_delete_conversation
 * PUT    /api/editor/agent/conversations/{id}/title         editor_agent_rename_conversation
 * GET    /api/editor/agent/conversations/{id}/messages      editor_agent_get_messages
 * POST   /api/editor/agent/resolve-script-key               editor_agent_resolve_script_key
 * POST   /api/editor/agent/conversations/{id}/clear         editor_agent_clear_conversation
 * GET    /api/editor/agent/conversations/{id}/artifacts     editor_agent_list_artifacts
 * POST   /api/editor/agent/conversations/{id}/chat          editor_agent_start_chat   → SSE
 * POST   /api/editor/agent/conversations/{id}/stop          editor_agent_stop_chat
 * POST   /api/editor/agent/conversations/{id}/rewind        editor_agent_rewind
 * POST   /api/editor/agent/approvals/{requestId}            editor_agent_resolve_approval
 * </pre>
 *
 * <p>错误码：会话/技能不存在 404，参数不合法 400 —— 由 {@code SkillAgentService} 抛
 * {@code ResponseStatusException}，Spring 默认转成对应状态码。
 */
@RestController
@RequestMapping("/api/editor/agent")
public class SkillAgentController {

    private final SkillAgentService service;

    public SkillAgentController(SkillAgentService service) {
        this.service = service;
    }

    // ───────────────── 设置 ─────────────────

    /** Agent 设置（沙箱目录为空表示默认 {@code data/}）。 */
    @GetMapping("/settings")
    public AgentSettings settings() {
        return service.getSettings();
    }

    /** 保存设置，回读一遍给前端。 */
    @PutMapping("/settings")
    public AgentSettings saveSettings(@RequestBody(required = false) AgentSettings request) {
        return service.saveSettings(request);
    }

    /** 设置面板展示的默认目录（数据根 / 技能库 / 沙箱）。 */
    @GetMapping("/default-dirs")
    public AgentDefaultDirs defaultDirs() {
        return service.getDefaultDirs();
    }

    // ───────────────── 技能 ─────────────────

    /** 技能库里的全部技能（目录不存在时为空数组）。 */
    @GetMapping("/skills")
    public List<SkillInfo> skills() {
        return service.listSkills();
    }

    /** 单个技能的 SKILL.md 内容；不存在 404。 */
    @GetMapping("/skills/{name}")
    public SkillContent skill(@PathVariable String name) {
        return service.readSkill(name);
    }

    // ───────────────── 会话 ─────────────────

    /** 新建会话（body 可选：{@code title} / {@code scriptKey}）。 */
    @PostMapping("/conversations")
    public ConversationInfo createConversation(@RequestBody(required = false) CreateConversationRequest request) {
        String title = request == null ? null : request.title();
        String scriptKey = request == null ? null : request.scriptKey();
        return service.createConversation(title, scriptKey);
    }

    /** 会话列表：最近更新在前。 */
    @GetMapping("/conversations")
    public List<ConversationInfo> conversations() {
        return service.listConversations();
    }

    /** 删会话连带删它的消息。 */
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable Long id) {
        service.deleteConversation(id);
        return ResponseEntity.noContent().build();
    }

    /** 重命名会话（标题非空）。 */
    @PutMapping("/conversations/{id}/title")
    public ResponseEntity<Void> renameConversation(@PathVariable Long id,
                                                   @RequestBody(required = false) RenameConversationRequest request) {
        service.renameConversation(id, request == null ? null : request.title());
        return ResponseEntity.noContent().build();
    }

    /** 某会话的全部消息，按 id 升序。 */
    @GetMapping("/conversations/{id}/messages")
    public List<PersistedMessage> messages(@PathVariable Long id) {
        return service.getMessages(id);
    }

    /** 会话归属的剧本 key（库里没绑就按历史写入路径反推）。 */
    @PostMapping("/resolve-script-key")
    public AgentScriptKeyView resolveScriptKey(@RequestBody(required = false) ResolveScriptKeyRequest request) {
        if (request == null || request.conversationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId 不能为空");
        }
        return new AgentScriptKeyView(service.resolveScriptKey(request.conversationId()));
    }

    /** 清空会话的消息（会话本身保留）。 */
    @PostMapping("/conversations/{id}/clear")
    public ResponseEntity<Void> clearConversation(@PathVariable Long id) {
        service.clearConversation(id);
        return ResponseEntity.noContent().build();
    }

    /** 会话所属剧本包 {@code .agent/} 下的流程产物。 */
    @GetMapping("/conversations/{id}/artifacts")
    public List<AgentArtifact> artifacts(@PathVariable Long id) {
        return service.listArtifacts(id);
    }

    // ───────────────── 对话 ─────────────────

    /**
     * 开始一轮对话（本批骨架）：SSE 推 status → message_delta → done 三条事件。
     *
     * <p>body 可选；E7a2 接 LLM 后 {@code message} 会成为必填。
     */
    @PostMapping(value = "/conversations/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long id, @RequestBody(required = false) StartChatRequest request) {
        return service.startChat(id, request == null ? null : request.message());
    }

    /** 停止这一轮生成（置取消标志 + abort 任务句柄）。 */
    @PostMapping("/conversations/{id}/stop")
    public ResponseEntity<Void> stopChat(@PathVariable Long id) {
        service.stopChat(id);
        return ResponseEntity.noContent().build();
    }

    /** 回溯：删掉 id &gt;= messageId 的消息。 */
    @PostMapping("/conversations/{id}/rewind")
    public ResponseEntity<Void> rewind(@PathVariable Long id,
                                       @RequestBody(required = false) RewindRequest request) {
        if (request == null || request.messageId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messageId 不能为空");
        }
        service.rewind(id, request.messageId());
        return ResponseEntity.noContent().build();
    }

    /** 决断一条待审批的命令（{@code allowed=true} 放行）。 */
    @PostMapping("/approvals/{requestId}")
    public ResponseEntity<Void> resolveApproval(@PathVariable String requestId,
                                                @RequestBody(required = false) ResolveApprovalRequest request) {
        service.resolveApproval(requestId, request != null && request.allowed());
        return ResponseEntity.noContent().build();
    }
}

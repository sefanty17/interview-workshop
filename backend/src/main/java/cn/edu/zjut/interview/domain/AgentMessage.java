package cn.edu.zjut.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 剧本编辑器 AI 助手的一条消息，对应 DDL {@code skill_agent_message}
 * （对齐原版 {@code db/entities/skill_agent_message.rs}）。
 *
 * <p>存的是 OpenAI 消息格式：{@code role} 取 user/assistant/tool/system，
 * {@code tool_calls} 是序列化后的 JSON 字符串（原版同为 {@code serde_json::to_string}），
 * 空 content 存 NULL（原版 {@code db.rs:131} 的同款处理）。
 */
@Entity
@Table(name = "skill_agent_message")
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "role", length = 16, nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "CLOB")
    private String content;

    /** assistant 的思考链（仅展示，不进入正式回复）。 */
    @Column(name = "reasoning", columnDefinition = "CLOB")
    private String reasoning;

    @Column(name = "prompt_tokens")
    private Long promptTokens;

    @Column(name = "completion_tokens")
    private Long completionTokens;

    /** 输入中命中缓存（cache read）的 token 数。 */
    @Column(name = "cached_tokens")
    private Long cachedTokens;

    @Column(name = "tool_calls", columnDefinition = "CLOB")
    private String toolCalls;

    @Column(name = "tool_call_id", length = 64)
    private String toolCallId;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(Long cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

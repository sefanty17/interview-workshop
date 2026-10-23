package cn.edu.zjut.interview.prompt;

import cn.edu.zjut.interview.domain.InterviewMessage;
import cn.edu.zjut.interview.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 台词表 → LLM 消息列表，对应 {@code game_system/memory_builder.rs:88 build}。
 *
 * <p>LingChat 侧还有「多角色 / 目标角色过滤 / tool_calls / 旁白上下文块」等逻辑，
 * 本项目只有单面试官 + user，因此只搬 {@code TargetAssistant} 与 {@code OtherBlock}
 * 两条分支里真正会用到的部分（规格 B0-BE §3.4）。
 *
 * <p>合并规则：
 * <ul>
 *   <li>{@code system}：只取第一条，重复的忽略并告警（Rust: has_system_for_target）</li>
 *   <li>{@code assistant}：连续多句合成一条，每句 {@code 【情绪】正文\n(动作)\n\n}，
 *       整段末尾再补一个 {@code \n}（Rust: {@code format!("{full}\n")}）</li>
 *   <li>{@code user}：连续多句合成一条，各句用 {@code \n} 连接，末尾不加换行
 *       （Rust: {@code user_text.join("\n")}）</li>
 *   <li>角色切换即断开缓冲</li>
 * </ul>
 */
public class MemoryBuilder {

    private static final Logger log = LoggerFactory.getLogger(MemoryBuilder.class);

    /** 缓冲段的类型，对应 Rust 的 {@code BufferKind}。 */
    private enum BufferKind {
        /** 面试官（assistant）连续发言。 */
        TARGET_ASSISTANT,
        /** user 连续发言（Rust 的 OtherBlock 单角色简化版）。 */
        USER_BLOCK
    }

    /**
     * 构建消息列表。
     *
     * @param messages 本次会话的台词，按 {@code seq} 升序（本方法内部再排一次，防御乱序）
     * @return role/content 序列，可直接喂 OpenAI 兼容接口
     */
    public List<LlmMessage> build(List<InterviewMessage> messages) {
        List<LlmMessage> memory = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return memory;
        }

        List<InterviewMessage> ordered = new ArrayList<>(messages);
        ordered.sort(Comparator.comparing(InterviewMessage::getSeq,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<InterviewMessage> buffer = new ArrayList<>();
        BufferKind bufferKind = null;
        boolean hasSystem = false;

        for (InterviewMessage message : ordered) {
            String role = message.getRole();

            // Rust: LineAttribute::System —— 先 flush，再决定是否收下
            if (InterviewMessage.ROLE_SYSTEM.equals(role)) {
                bufferKind = flush(memory, buffer, bufferKind);
                if (hasSystem) {
                    log.warn("[MemoryBuilder] 已存在 System 台词，跳过重复项 (seq={})", message.getSeq());
                } else {
                    hasSystem = true;
                    memory.add(LlmMessage.system(nullToEmpty(message.getContent())));
                }
                continue;
            }

            if (InterviewMessage.ROLE_ASSISTANT.equals(role)) {
                if (bufferKind == BufferKind.USER_BLOCK) {
                    bufferKind = flush(memory, buffer, bufferKind);
                }
                bufferKind = BufferKind.TARGET_ASSISTANT;
                buffer.add(message);
            } else if (InterviewMessage.ROLE_USER.equals(role)) {
                if (bufferKind == BufferKind.TARGET_ASSISTANT) {
                    bufferKind = flush(memory, buffer, bufferKind);
                }
                bufferKind = BufferKind.USER_BLOCK;
                buffer.add(message);
            } else {
                // 值域之外的 role（DDL 只允许 system/user/assistant），与 Rust 一样直接跳过
                log.warn("[MemoryBuilder] 未知 role={}，已跳过 (seq={})", role, message.getSeq());
            }
        }

        flush(memory, buffer, bufferKind);
        return memory;
    }

    /**
     * 冲掉当前缓冲，返回新的缓冲类型（Rust 的 {@code flush} 闭包把 buffer_kind 置 None）。
     */
    private BufferKind flush(List<LlmMessage> memory, List<InterviewMessage> buffer, BufferKind bufferKind) {
        if (buffer.isEmpty()) {
            return null;
        }
        switch (bufferKind) {
            case TARGET_ASSISTANT -> {
                StringBuilder full = new StringBuilder();
                for (InterviewMessage message : buffer) {
                    full.append(formatAssistantLine(message));
                }
                // Rust: if !full.trim().is_empty()
                if (!full.toString().trim().isEmpty()) {
                    // 段末再补一个 \n：台词之间只有一个空行，整段结束是两个空行
                    memory.add(LlmMessage.assistant(full.toString() + "\n"));
                }
            }
            case USER_BLOCK -> {
                // Rust: parts.push(user_text.join("\n"))，多条台词用换行连接，段末不再补换行
                List<String> userTexts = new ArrayList<>(buffer.size());
                for (InterviewMessage message : buffer) {
                    userTexts.add(nullToEmpty(message.getContent()));
                }
                memory.add(LlmMessage.user(String.join("\n", userTexts)));
            }
            default -> {
            }
        }
        buffer.clear();
        return null;
    }

    /**
     * 单条面试官台词，对应 {@code memory_builder.rs:43 format_content_with_extras}。
     *
     * <p>{@code 【情绪】正文\n(动作)\n\n}；没有动作时是 {@code 【情绪】正文\n\n}。
     * Rust 里还有 {@code <tts>} 一段，本项目不做 TTS，没有对应字段，故省略。
     * 正文按 Rust 的 {@code trim_end()} 去尾部空白。
     */
    private static String formatAssistantLine(InterviewMessage message) {
        StringBuilder s = new StringBuilder();
        String emotion = message.getOriginalTag();
        if (emotion != null && !emotion.isEmpty()) {
            s.append('【').append(emotion).append('】');
        }
        s.append(nullToEmpty(message.getContent()).stripTrailing());
        s.append('\n');
        String action = message.getAction();
        if (action != null && !action.isEmpty()) {
            s.append('(').append(action).append(')').append('\n');
        }
        s.append('\n');
        return s.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

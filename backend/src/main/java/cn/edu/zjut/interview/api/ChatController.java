package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.SendMessageRequest;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;

/**
 * 自由对话接口（规格 F-1）。
 *
 * <p>{@code POST /api/sessions/{id}/messages} 对应原版 {@code api/chat.rs::send_chat_message}：
 * 与剧本无关的一条独立链路，落用户台词 → 拼记忆 → 调模型 → 分句 → SSE 回推。
 * SSE 事件名沿用 LingChat 原名：{@code ai:thinking / ai:reply / status:reset / ai:error}，
 * 其中 {@code ai:reply} 的 data 仍是 {@code dto/ReplyEvent}。
 *
 * <p>剧本的推进与输入走 {@link ScriptPlayController}（{@code /{id}/script/*}），
 * 两条链路互不兼容：剧本模式下 {@code /messages} 不会推动剧本进度（原版同样如此）。
 *
 * <p>批次 4.4 新增档位读写（对齐 {@code chat.rs:58-75} 的 get/set_reply_length_mode）：
 * {@code GET/PUT /api/chat/sessions/{id}/reply-length-mode}。试玩会话 GET 返回
 * {@code {"mode":null}}、PUT 返回 409（对齐 pending_preview_restore 语义）。
 */
@RestController
public class ChatController {

    private final InterviewEngine engine;
    private final ChatService chatService;

    public ChatController(InterviewEngine engine, ChatService chatService) {
        this.engine = engine;
        this.chatService = chatService;
    }

    @PostMapping(value = "/api/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable Long id, @RequestBody SendMessageRequest request) {
        // 场次不存在时在调用线程里直接 404，不把 404 变成流里的 error 事件
        engine.ensureExists(id);
        String text = request == null || request.text() == null ? "" : request.text();
        return chatService.processMessage(id, text);
    }

    /** 读当前档位；试玩会话返回 {"mode":null}。 */
    @GetMapping("/api/chat/sessions/{id}/reply-length-mode")
    public ResponseEntity<Map<String, String>> getReplyLengthMode(@PathVariable Long id) {
        String mode = chatService.getReplyLengthMode(id);
        return ResponseEntity.ok(Collections.singletonMap("mode", mode));
    }

    /** 改档位：body {"mode":"short|normal|free"}，非法值 400、试玩会话 409。 */
    @PutMapping("/api/chat/sessions/{id}/reply-length-mode")
    public ResponseEntity<Map<String, String>> setReplyLengthMode(@PathVariable Long id,
                                                                  @RequestBody Map<String, String> body) {
        if (body == null || !body.containsKey("mode")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 mode 字段");
        }
        String mode = chatService.setReplyLengthMode(id, body.get("mode"));
        return ResponseEntity.ok(Collections.singletonMap("mode", mode));
    }
}

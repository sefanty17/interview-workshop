package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.dto.SendMessageRequest;
import cn.edu.zjut.interview.engine.InterviewEngine;
import cn.edu.zjut.interview.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 自由对话接口（规格 F-1）。
 *
 * <p>{@code POST /{id}/messages} 对应原版 {@code api/chat.rs::send_chat_message}：
 * 与剧本无关的一条独立链路，落用户台词 → 拼记忆 → 调模型 → 分句 → SSE 回推。
 * SSE 事件名沿用 LingChat 原名：{@code ai:thinking / ai:reply / status:reset / ai:error}，
 * 其中 {@code ai:reply} 的 data 仍是 {@code dto/ReplyEvent}。
 *
 * <p>剧本的推进与输入走 {@link ScriptPlayController}（{@code /{id}/script/*}），
 * 两条链路互不兼容：剧本模式下 {@code /messages} 不会推动剧本进度（原版同样如此）。
 */
@RestController
@RequestMapping("/api/sessions")
public class ChatController {

    private final InterviewEngine engine;
    private final ChatService chatService;

    public ChatController(InterviewEngine engine, ChatService chatService) {
        this.engine = engine;
        this.chatService = chatService;
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable Long id, @RequestBody SendMessageRequest request) {
        // 场次不存在时在调用线程里直接 404，不把 404 变成流里的 error 事件
        engine.ensureExists(id);
        String text = request == null || request.text() == null ? "" : request.text();
        return chatService.processMessage(id, text);
    }
}

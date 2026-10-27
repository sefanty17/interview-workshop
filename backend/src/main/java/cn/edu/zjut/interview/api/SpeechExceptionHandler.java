package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.speech.asr.AsrException;
import cn.edu.zjut.interview.speech.tts.TtsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音线的统一错误出口：把 {@link AsrException} / {@link TtsException} 转成前端能直接吃下的结构。
 *
 * <p>响应体固定三个键：{@code code}（{@code ASR_xxx} / {@code TTS_xxx}，前端按它查 i18n）、
 * {@code detail}（人话，文案改了不影响前端逻辑）、{@code status}（回显状态码）。
 *
 * <p>为什么不用 {@code ProblemDetail}：它靠 Spring 注册的
 * {@code ProblemDetailJacksonMixin} 把扩展属性摊平到顶层，而本项目的
 * {@code ObjectMapper} 是手工 {@code new} 的（见 {@code JacksonConfig}），Boot 的 Jackson
 * 自动配置因此让位、mixin 没被注册——直接返回会得到
 * {@code {"code" 藏在 properties 子对象里}}，前端 {@code res.code} 永远是 undefined。
 * 用显式 Map 就不依赖那层自动配置。
 *
 * <p>只处理这两个语音异常：现有控制器各自用手工 {@code @ExceptionHandler}
 * 决定错误体形状（如 {@code SettingsController} 的 {@code {"error": ...}}），
 * 这里若接 {@code Exception} 就会抢走它们的处理逻辑。
 *
 * <p>TTS 与 ASR 共用一个 Advice 而不是各写一个：响应体形状完全一样，分成两个类只会有
 * 一份被 Spring 按 {@code @Order} 抢先生效，将来改一处忘一处就会让两条线返回不同的键。
 */
@RestControllerAdvice
public class SpeechExceptionHandler {

    @ExceptionHandler(AsrException.class)
    public ResponseEntity<Map<String, Object>> handleAsr(AsrException e) {
        return error(e.kind().code(), e.kind().defaultMessage(), e.getMessage(), e.status());
    }

    @ExceptionHandler(TtsException.class)
    public ResponseEntity<Map<String, Object>> handleTts(TtsException e) {
        return error(e.kind().code(), e.kind().defaultMessage(), e.getMessage(), e.status());
    }

    private static ResponseEntity<Map<String, Object>> error(String code, String defaultMessage,
                                                             String message, HttpStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("detail", message == null ? defaultMessage : message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}

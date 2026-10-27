package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.speech.asr.AsrException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音线的统一错误出口：把 {@link AsrException} 转成前端能直接吃下的结构。
 *
 * <p>响应体固定三个键：{@code code}（{@code ASR_xxx}，前端 {@code parseAsrError}
 * 按它查 i18n）、{@code detail}（人话，文案改了不影响前端逻辑）、{@code status}（回显状态码）。
 *
 * <p>为什么不用 {@code ProblemDetail}：它靠 Spring 注册的
 * {@code ProblemDetailJacksonMixin} 把扩展属性摊平到顶层，而本项目的
 * {@code ObjectMapper} 是手工 {@code new} 的（见 {@code JacksonConfig}），Boot 的 Jackson
 * 自动配置因此让位、mixin 没被注册——直接返回会得到
 * {@code {"code" 藏在 properties 子对象里}}，前端 {@code res.code} 永远是 undefined。
 * 用显式 Map 就不依赖那层自动配置。
 *
 * <p>只处理 {@link AsrException}：现有控制器各自用手工 {@code @ExceptionHandler}
 * 决定错误体形状（如 {@code SettingsController} 的 {@code {"error": ...}}），
 * 这里若接 {@code Exception} 就会抢走它们的处理逻辑。
 */
@RestControllerAdvice
public class SpeechExceptionHandler {

    @ExceptionHandler(AsrException.class)
    public ResponseEntity<Map<String, Object>> handleAsr(AsrException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.kind().code());
        body.put("detail", e.getMessage() == null ? e.kind().defaultMessage() : e.getMessage());
        body.put("status", e.status().value());
        return ResponseEntity.status(e.status()).body(body);
    }
}

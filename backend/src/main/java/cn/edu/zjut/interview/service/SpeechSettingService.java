package cn.edu.zjut.interview.service;

import cn.edu.zjut.interview.domain.SpeechSetting;
import cn.edu.zjut.interview.repository.SpeechSettingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * 语音设置（ASR / TTS）的整份读写，对应原版 {@code ai_service/asr/settings.rs} 的
 * {@code load} / {@code save}，只是存储从 {@code settings.json} 换成了数据库。
 *
 * <p>只负责「把一份对象原样存成 JSON 再原样读回来」，不认识任何具体设置字段；
 * 字段默认值与校验放在 {@code AsrSettingsService}——那是 ASR 的领域知识，不该泄进通用存储层。
 *
 * <p>本类<strong>不抛</strong>「没有设置」这种异常：没有行就返回调用方给的兜底值，
 * 这样「全新用户」和「老用户」走同一条代码路径，不必在每个读点判空。
 */
@Service
public class SpeechSettingService {

    /** ASR 设置的 kind。 */
    public static final String KIND_ASR = "asr";
    /** TTS 设置的 kind（V1 只占位，语音线后续批次使用）。 */
    public static final String KIND_TTS = "tts";

    private static final Logger log = LoggerFactory.getLogger(SpeechSettingService.class);

    private final SpeechSettingRepository repository;

    /**
     * 反序列化专用的 mapper：在注入的 mapper 基础上关掉「未知字段报错」。
     *
     * <p>为什么必须关：原版 {@code ProviderConfig} 没有 {@code deny_unknown_fields}，
     * 前端多传一个字段（比如新版本前端配老版本后端）只是被忽略，不能变成 400。
     * 项目全局 mapper 是 {@code new ObjectMapper()}（默认开启该开关），所以这里 copy 一份改配置，
     * 不动全局对象——别的模块可能正依赖严格模式。
     */
    private final ObjectMapper lenientMapper;

    public SpeechSettingService(SpeechSettingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.lenientMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 读某类设置；没有行、payload 为空、或 JSON 解析失败 → 返回 {@code fallback} 并记 warn。
     *
     * <p>解析失败不让接口 500：设置页打不开比「用了默认值」严重得多，原版
     * {@code settings::load} 对 malformed JSON 也只是 warn + 保持 defaults。
     */
    public <T> T read(String kind, Class<T> type, T fallback) {
        String payload = repository.findByKind(kind).map(SpeechSetting::getPayload).orElse(null);
        if (payload == null || payload.isBlank()) {
            return fallback;
        }
        try {
            T value = lenientMapper.readValue(payload, type);
            return value == null ? fallback : value;
        } catch (JsonProcessingException e) {
            log.warn("[SpeechSetting] {} 的 payload 解析失败，回退默认值：{}", kind, e.getOriginalMessage());
            return fallback;
        }
    }

    /**
     * 整份覆盖写：同一 kind 只留一行（有则更新 payload + updated_at）。
     *
     * <p>序列化失败直接 500 而不吞掉：存进去一个坏 JSON，下次读会静默回退默认值，
     * 用户的设置就「看起来保存成功、重启后全没了」，这种错误必须当场暴露。
     */
    @Transactional
    public void write(String kind, Object value) {
        String payload;
        try {
            payload = lenientMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "语音设置序列化失败：" + kind, e);
        }
        LocalDateTime now = LocalDateTime.now();
        SpeechSetting row = repository.findByKind(kind)
                .orElseGet(() -> new SpeechSetting(null, kind, payload, now));
        row.setPayload(payload);
        row.setUpdatedAt(now);
        repository.save(row);
    }

    /** 删掉某类设置（回到「没有行 = 用默认值」）。测试清理用，不对外暴露端点。 */
    @Transactional
    public void delete(String kind) {
        repository.findByKind(kind).ifPresent(repository::delete);
    }
}

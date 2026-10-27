package cn.edu.zjut.interview.speech.asr;

import cn.edu.zjut.interview.domain.SpeechSetting;
import cn.edu.zjut.interview.repository.SpeechSettingRepository;
import cn.edu.zjut.interview.service.SpeechSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASR 设置的持久化语义（规格 V1 §4.1）。
 *
 * <p>重点验证「读」的三条兜底路径：没有行、字段比代码新、payload 损坏——
 * 三者都必须返回可用设置而不是抛异常，否则一条脏数据就能让设置页整个打不开。
 */
@SpringBootTest
@ActiveProfiles("test")
class AsrSettingsServiceTest {

    @Autowired
    private SpeechSettingService service;

    @Autowired
    private SpeechSettingRepository repository;

    @AfterEach
    void 清理设置() {
        service.delete(SpeechSettingService.KIND_ASR);
        service.delete(SpeechSettingService.KIND_TTS);
    }

    @Test
    void 无行时读到默认值() {
        AsrSettings settings = service.read(SpeechSettingService.KIND_ASR, AsrSettings.class,
                AsrSettings.defaults());

        assertEquals("qwen-asr", settings.getActiveProvider());
        assertEquals(800, settings.getVadSilenceMs());
        assertFalse(settings.isVoiceInputEnabled());
        assertEquals("{\"key\":\"f8\"}", settings.getPttKey());
        assertTrue(settings.getProviderConfigs().containsKey("qwen-asr"));
        assertTrue(settings.getProviderConfigs().containsKey("llama-asr"));
    }

    @Test
    void 写入后原样读回() {
        AsrSettings settings = AsrSettings.defaults();
        settings.setVadSilenceMs(1200);
        settings.getProviderConfigs().get("qwen-asr").getApiKeys().put("cn-beijing", "sk-test");

        service.write(SpeechSettingService.KIND_ASR, settings);
        AsrSettings loaded = service.read(SpeechSettingService.KIND_ASR, AsrSettings.class,
                AsrSettings.defaults());

        assertEquals(1200, loaded.getVadSilenceMs());
        assertEquals("sk-test", loaded.getProviderConfigs().get("qwen-asr").getApiKeys().get("cn-beijing"));
        assertEquals("sk-test", loaded.getProviderConfigs().get("qwen-asr").effectiveApiKey());
    }

    @Test
    void 同一kind只留一行() {
        AsrSettings first = AsrSettings.defaults();
        first.setVadSilenceMs(300);
        service.write(SpeechSettingService.KIND_ASR, first);

        AsrSettings second = AsrSettings.defaults();
        second.setVadSilenceMs(1500);
        service.write(SpeechSettingService.KIND_ASR, second);

        assertEquals(1, repository.findAll().size());
        assertEquals(1500, service.read(SpeechSettingService.KIND_ASR, AsrSettings.class,
                AsrSettings.defaults()).getVadSilenceMs());
    }

    @Test
    void 未知字段被忽略() {
        // 直接塞库：模拟「前端版本比后端新」，多出来的字段不能让整份设置读不出来
        repository.save(new SpeechSetting(null, SpeechSettingService.KIND_ASR,
                "{\"active_provider\":\"mock-asr\",\"future_field\":1}", LocalDateTime.now()));

        AsrSettings loaded = service.read(SpeechSettingService.KIND_ASR, AsrSettings.class,
                AsrSettings.defaults());

        assertEquals("mock-asr", loaded.getActiveProvider());
    }

    @Test
    void payload损坏时返回兜底() {
        repository.save(new SpeechSetting(null, SpeechSettingService.KIND_ASR, "{not json",
                LocalDateTime.now()));

        AsrSettings loaded = assertDoesNotThrow(() -> service.read(SpeechSettingService.KIND_ASR,
                AsrSettings.class, AsrSettings.defaults()));

        assertEquals("qwen-asr", loaded.getActiveProvider());
    }

    /** 本项目扩展的 6 个字段（上游没有）必须跟着整份设置一起有默认值，
     *  否则设置页那 6 个控件在全新用户下会读到 0 / null 渲染崩。 */
    @Test
    void 扩展字段的默认值六项正确() {
        AsrSettings settings = AsrSettings.defaults();

        assertFalse(settings.isWakeWordEnabled());
        assertEquals("你好面试官", settings.getWakeWord());
        assertTrue(settings.isAsrContinuous());
        assertTrue(settings.isAsrMicGate());
        assertEquals(0.5, settings.getAsrMicThreshold(), 1e-9);
        assertEquals("default", settings.getAsrMicDevice());
    }

    /** 改其中两项 → 落库 → 读回一致（验证 6 个扩展字段真的进了 JSON，不是只在内存里） */
    @Test
    void 扩展字段写入后读回一致() {
        AsrSettings settings = AsrSettings.defaults();
        settings.setWakeWord("小面试官");
        settings.setAsrMicThreshold(0.75);
        service.write(SpeechSettingService.KIND_ASR, settings);

        AsrSettings loaded = service.read(SpeechSettingService.KIND_ASR, AsrSettings.class,
                AsrSettings.defaults());

        assertEquals("小面试官", loaded.getWakeWord());
        assertEquals(0.75, loaded.getAsrMicThreshold(), 1e-9);
    }
}

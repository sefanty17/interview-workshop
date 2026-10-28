package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.speech.tts.adapters.CosyvoiceAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TtsVoiceService} 的短路顺序与「失败静默」（规格 V6a §6.1）。
 *
 * <p>{@link TtsService} 与 {@link TtsSettingsService} 用 Mockito 桩掉（真适配器要发 HTTP），
 * {@link VoiceFileService} 用真实例 + {@code @TempDir}：缓存命中/落盘是这一批的核心行为，
 * 必须落在真磁盘上断言，不能靠 mock 自己记账。
 */
class TtsVoiceServiceTest {

    private static final byte[] AUDIO = {1, 2, 3, 4, 5, 6};

    private static final String URL_PREFIX = "/api/speech/audio/";

    @TempDir
    private Path dir;

    private VoiceFileService files;
    private TtsService ttsService;
    private TtsSettingsService settingsService;
    private TtsVoiceService service;

    @BeforeEach
    void 起桩() throws IOException {
        files = new VoiceFileService(dir, VoiceFileService.DEFAULT_MAX_FILES);
        ttsService = mock(TtsService.class);
        settingsService = mock(TtsSettingsService.class);
        when(settingsService.load()).thenReturn(settings(true, CosyvoiceAdapter.TYPE, "wav"));
        service = new TtsVoiceService(settingsService, ttsService, files);
    }

    @Test
    void 开关关闭时不合成() {
        when(settingsService.load()).thenReturn(settings(false, CosyvoiceAdapter.TYPE, "wav"));

        assertEquals(Optional.empty(), service.synthesize("你好", "中立"), "总开关关着，一次请求都不许发");
        verify(ttsService, never()).synthesizeAs(anyString(), anyString(), anyString());
    }

    @Test
    void 不支持的引擎静默跳过() {
        // edge-tts 是「本项目确定不做」，且它就是默认值：这里必须静默，否则一开 TTS 就满屏报错
        when(settingsService.load()).thenReturn(settings(true, "edge-tts", "wav"));

        assertEquals(Optional.empty(), service.synthesize("你好", "中立"));
        verify(ttsService, never()).synthesizeAs(anyString(), anyString(), anyString());
    }

    @Test
    void 空白文本不合成() {
        assertEquals(Optional.empty(), service.synthesize("   ", "中立"));
        assertEquals(Optional.empty(), service.synthesize(null, "中立"), "null 文本同样不该打到适配器");
        verify(ttsService, never()).synthesizeAs(anyString(), anyString(), anyString());
    }

    @Test
    void 合成成功返回URL且落盘() throws IOException {
        when(ttsService.synthesizeAs(eq(CosyvoiceAdapter.TYPE), eq("你好"), eq("中立"))).thenReturn(AUDIO);

        Optional<String> url = service.synthesize("你好", "中立");

        assertTrue(url.isPresent(), "合成成功必须给出 URL");
        assertTrue(url.get().startsWith(URL_PREFIX), "URL 指向静态端点：" + url.get());
        String name = url.get().substring(URL_PREFIX.length());
        Optional<Path> file = files.resolve(name);
        assertTrue(file.isPresent(), "URL 里的名字必须能在缓存目录里解析到真文件");
        assertArrayEquals(AUDIO, Files.readAllBytes(file.get()), "落盘字节应与适配器返回的一致");
    }

    @Test
    void 同参数命中缓存不重复合成() {
        when(ttsService.synthesizeAs(eq(CosyvoiceAdapter.TYPE), eq("你好"), eq("中立"))).thenReturn(AUDIO);

        Optional<String> first = service.synthesize("你好", "中立");
        Optional<String> second = service.synthesize("你好", "中立");

        assertEquals(first, second, "同一句第二次必须走缓存、返回同一个 URL");
        verify(ttsService, times(1)).synthesizeAs(eq(CosyvoiceAdapter.TYPE), eq("你好"), eq("中立"));
    }

    @Test
    void 适配器抛异常时返回空() {
        when(ttsService.synthesizeAs(anyString(), anyString(), anyString()))
                .thenThrow(TtsException.requestFailed("桩"));

        assertEquals(Optional.empty(), service.synthesize("你好", "中立"),
                "合成失败只表现为「本句没声音」，不往外抛");
    }

    // ---------------- 工具 ----------------

    private static TtsSettings settings(boolean ttsOn, String type, String format) {
        TtsSettings settings = TtsSettings.defaults();
        settings.setTtsOn(ttsOn);
        settings.setTtsType(type);
        settings.setTtsAudioFormat(format);
        return settings;
    }
}

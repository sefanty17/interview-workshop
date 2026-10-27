package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.speech.tts.TtsService;
import cn.edu.zjut.interview.speech.tts.TtsSettings;
import cn.edu.zjut.interview.speech.tts.TtsSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语音合成接口（V5a）：设置读写 + 引擎清单 + 试听探活。
 *
 * <pre>
 * GET  /api/speech/tts/settings   当前设置（无行 → 默认值）
 * PUT  /api/speech/tts/settings   整份覆盖（未知 ttsType → 400 TTS_ENGINE_UNSUPPORTED）
 * GET  /api/speech/tts/engines    13 个引擎（不支持的 supported=false + 原因）
 * POST /api/speech/tts/test       合成一小段，返回 {ok, type, latencyMs, bytes}
 * </pre>
 *
 * <p><b>键名一律 camelCase</b>，与 {@code SettingsTts.vue} / {@code mock.ts} 的字段逐字对齐：
 * 前端把整份 {@code store.settings} 直接 PUT 上来、把响应直接塞回去，中间不做重命名。
 * 这是 TTS 与 ASR 的刻意差异（ASR 对齐的是上游 Rust 结构体，所以是 snake_case）。
 *
 * <p>本批<strong>不提供</strong> {@code /preview} 与音频文件服务：试听需要把合成结果落盘
 * 再按 id 提供静态资源，属于 V6（前端播放器接线）的范围；
 * {@code /test} 只回字节数与耗时，用来回答「这套参数能不能打通」。
 */
@RestController
@RequestMapping("/api/speech/tts")
public class TtsController {

    /** 未指定文本时的探活文本：短、含中文，能顺带验证编码链路（中文乱码时上游多半 400）。 */
    private static final String DEFAULT_TEST_TEXT = "语音测试";

    private final TtsSettingsService settingsService;
    private final TtsService ttsService;

    public TtsController(TtsSettingsService settingsService, TtsService ttsService) {
        this.settingsService = settingsService;
        this.ttsService = ttsService;
    }

    /** 当前设置。读的时候修野值（未知 ttsType 回退默认），前端无需自己兜。 */
    @GetMapping("/settings")
    public TtsSettings settings() {
        return settingsService.load();
    }

    /**
     * 整份覆盖设置。
     *
     * <p>回显保存后的对象：前端不必再 GET 一次就能刷新表单（表单里可能有 Jackson 补上的默认值）。
     */
    @PutMapping("/settings")
    public TtsSettings saveSettings(@RequestBody(required = false) TtsSettings body) {
        settingsService.save(body);
        return body;
    }

    /**
     * 引擎清单：13 项，顺序固定（= 设置页下拉顺序）。
     *
     * <p>{@code supported=false} 的两类：本项目确定不做的（edge-tts / local-sbv2，{@code note} 写原因）
     * 与排期在后续批次的（{@code note} 统一为「尚未接入」）。
     */
    @GetMapping("/engines")
    public List<TtsService.TtsEngineInfo> engines() {
        return ttsService.engines();
    }

    /**
     * 试听探活：用当前（或指定）引擎合成 {@value #DEFAULT_TEST_TEXT}。
     *
     * <p>失败不在这里捕获：{@link cn.edu.zjut.interview.speech.tts.TtsException} 由
     * {@link SpeechExceptionHandler} 统一转成 {@code {code, detail, status}}，
     * 前端据 {@code code} 区分「没配地址」和「上游 500」。
     *
     * <p>{@code ttsType} 只在本次调用内生效，不回写设置——设置页的「测试」按钮要在保存前试参数。
     */
    @PostMapping("/test")
    public TestView test(@RequestBody(required = false) TestRequest request) {
        TtsSettings settings = settingsService.load();
        String type = request == null || request.ttsType() == null || request.ttsType().isBlank()
                ? settings.getTtsType()
                : request.ttsType().trim();
        String text = request == null || request.text() == null || request.text().isBlank()
                ? DEFAULT_TEST_TEXT
                : request.text().trim();

        long startedAt = System.nanoTime();
        byte[] audio = ttsService.synthesizeAs(type, text, settings.getTtsEmotion());
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return new TestView(true, type, latencyMs, audio.length);
    }

    /** 探活请求体；两个字段都可省，省了就用当前设置的引擎与默认文本。 */
    public record TestRequest(String ttsType, String text) {
    }

    /**
     * 探活结果。
     *
     * @param ok        恒为 true（失败走异常），只为前端少写一个分支
     * @param type      实际用到的引擎
     * @param latencyMs 耗时（毫秒），前端用它判断「是不是卡住了」
     * @param bytes     合成出的音频字节数
     */
    public record TestView(boolean ok, String type, long latencyMs, int bytes) {
    }
}

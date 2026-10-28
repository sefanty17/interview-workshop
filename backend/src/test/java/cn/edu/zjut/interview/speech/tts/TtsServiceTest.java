package cn.edu.zjut.interview.speech.tts;

import cn.edu.zjut.interview.service.SpeechSettingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TtsService} 的路由、禁用与恢复语义（规格 V5a §6.2）。
 *
 * <p>桩仍是 JDK {@link HttpServer}，状态码由 {@link #status} 控制：本类关心的不是
 * 「请求内容对不对」（那是 {@code TtsAdapterRequestTest} 的事），而是
 * 「上游 500 时这层怎么记账」。
 *
 * <p>每个用例前后都 {@code reactivate()}：{@link TtsService} 是单例 Bean，
 * 禁用标记是内存态，前一个用例把 TTS 关掉会让后一个用例莫名其妙地拿到
 * {@code TTS_DISABLED}（表现为「单独跑绿、一起跑红」）。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TtsServiceTest {

    private static final byte[] AUDIO = {1, 2, 3, 4, 5};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TtsService ttsService;

    @Autowired
    private TtsSettingsService settingsService;

    @Autowired
    private SpeechSettingService store;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void 起桩并复位() throws IOException {
        status.set(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int code = status.get();
            if (code >= 400) {
                byte[] body = ("{\"error\":\"桩返回 " + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(code, AUDIO.length);
                exchange.getResponseBody().write(AUDIO);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ttsService.reactivate();
    }

    @AfterEach
    void 停桩并清理() {
        server.stop(0);
        ttsService.reactivate();
        store.delete(SpeechSettingService.KIND_TTS);
    }

    @Test
    void 未配置时抛缺参数而不是未接入() {
        保存("sbv2", Map.of("apiUrl", "", "modelName", "interviewer", "speakerId", 0));

        TtsException e = assertThrows(TtsException.class, () -> ttsService.synthesize("你好", ""));

        assertEquals(TtsErrorKind.NOT_CONFIGURED, e.kind(), "引擎接了但没配地址 → 缺参数，不是「未接入」");
        assertTrue(e.getMessage().contains("apiUrl"), "message 必须指名 apiUrl：" + e.getMessage());
    }

    @Test
    void 不支持的引擎抛未接入() {
        for (String type : new String[] {"edge-tts", "local-sbv2"}) {
            保存(type, Map.of());
            TtsException e = assertThrows(TtsException.class, () -> ttsService.synthesize("你好", ""),
                    type + " 应该抛未接入");
            assertEquals(TtsErrorKind.ENGINE_UNSUPPORTED, e.kind(), type);
            assertTrue(e.getMessage().contains(type), "message 要能看出是哪个引擎：" + e.getMessage());
        }
    }

    @Test
    void 连续失败三次后禁用且可恢复() {
        status.set(500);
        保存("bv2", Map.of("apiUrl", baseUrl, "speakerId", 0));

        for (int i = 1; i <= TtsService.MAX_CONSECUTIVE_FAILURES; i++) {
            final int attempt = i;
            TtsException e = assertThrows(TtsException.class, () -> ttsService.synthesize("第" + attempt + "句", ""));
            assertEquals(TtsErrorKind.REQUEST_FAILED, e.kind(), "第 " + attempt + " 次失败应先抛真实错误");
        }
        assertFalse(ttsService.isEnabled(), "连续 3 次失败后应被禁用");

        TtsException disabled = assertThrows(TtsException.class, () -> ttsService.synthesize("第四句", ""));
        assertEquals(TtsErrorKind.DISABLED, disabled.kind());

        ttsService.reactivate();
        assertTrue(ttsService.isEnabled());
        status.set(200);
        assertDoesNotThrow(() -> ttsService.synthesize("恢复后", ""));
    }

    @Test
    void 成功一次把失败计数清零() {
        保存("bv2", Map.of("apiUrl", baseUrl, "speakerId", 0));

        // 500 → 500 → 200 → 500 → 500：累计 4 次失败，但中间成功过，计数在中间被清零
        int[] codes = {500, 500, 200, 500, 500};
        int round = 0;
        for (int code : codes) {
            round++;
            final int current = round;
            status.set(code);
            if (code >= 400) {
                assertThrows(TtsException.class, () -> ttsService.synthesize("第" + current + "句", ""));
            } else {
                assertDoesNotThrow(() -> ttsService.synthesize("正常句", ""));
            }
        }
        assertTrue(ttsService.isEnabled(), "成功过一次就说明服务可用，累计 4 次失败不该禁用");

        status.set(500);
        TtsException e = assertThrows(TtsException.class, () -> ttsService.synthesize("再接一句", ""));
        assertEquals(TtsErrorKind.REQUEST_FAILED, e.kind(), "禁用要等「连续」3 次，这里只连续失败 2 次");
    }

    @Test
    void PUT未知引擎返回400且带错误码() throws Exception {
        mockMvc.perform(put("/api/speech/tts/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttsType\":\"not-an-engine\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TTS_ENGINE_UNSUPPORTED"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void 默认引擎edgeTts可以保存但合成报未接入() throws Exception {
        // 前端 ttsType 的默认值就是 edge-tts：保存不能拦，否则用户不改设置点保存就 400
        mockMvc.perform(put("/api/speech/tts/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttsOn\":true,\"ttsType\":\"edge-tts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttsType").value("edge-tts"))
                .andExpect(jsonPath("$.ttsTimeout").value(30));

        TtsException e = assertThrows(TtsException.class, () -> ttsService.synthesize("你好", ""));
        assertEquals(TtsErrorKind.ENGINE_UNSUPPORTED, e.kind());
    }

    @Test
    void 引擎清单13项且不支持的带原因() throws Exception {
        mockMvc.perform(get("/api/speech/tts/engines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13))
                .andExpect(jsonPath("$[0].type").value("edge-tts"))
                .andExpect(jsonPath("$[0].supported").value(false))
                .andExpect(jsonPath("$[0].note").isNotEmpty())
                // 本批（V5a）已接入的四个
                .andExpect(jsonPath("$[2].type").value("sbv2"))
                .andExpect(jsonPath("$[2].supported").value(true))
                .andExpect(jsonPath("$[8].type").value("vits"))
                .andExpect(jsonPath("$[8].supported").value(true))
                .andExpect(jsonPath("$[12].type").value("local-sbv2"))
                .andExpect(jsonPath("$[12].supported").value(false))
                // V5b 接入的四个：gpt-sovits / cosyvoice / fish-speech / gsv
                .andExpect(jsonPath("$[1].type").value("gpt-sovits"))
                .andExpect(jsonPath("$[1].supported").value(true))
                .andExpect(jsonPath("$[4].type").value("cosyvoice"))
                .andExpect(jsonPath("$[4].supported").value(true))
                .andExpect(jsonPath("$[6].type").value("fish-speech"))
                .andExpect(jsonPath("$[6].supported").value(true))
                .andExpect(jsonPath("$[11].type").value("gsv"))
                .andExpect(jsonPath("$[11].supported").value(true));
    }

    /** 把某引擎的配置写进库；其余 12 个引擎保留默认配置（模拟前端整份 PUT）。 */
    private void 保存(String type, Map<String, Object> cfg) {
        TtsSettings settings = new TtsSettings();
        settings.setTtsOn(true);
        settings.setTtsType(type);
        Map<String, Map<String, Object>> configs = new LinkedHashMap<>(settings.getTtsEngineConfigs());
        configs.put(type, new LinkedHashMap<>(cfg));
        settings.setTtsEngineConfigs(configs);
        settingsService.save(settings);
    }
}

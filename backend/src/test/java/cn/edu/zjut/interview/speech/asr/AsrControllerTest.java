package cn.edu.zjut.interview.speech.asr;

import cn.edu.zjut.interview.service.SpeechSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语音识别端点（规格 V1 §4.3）。
 *
 * <p>连接层是 {@code /api/speech/asr}，JSON 键一律 snake_case——与上游 serde
 * 和 LingChat 前端 {@code api/services/asr.ts} 一致。
 *
 * <p>每个用例结束都删掉 {@code asr} 行的设置：这些用例共享同一个 Spring 上下文与内存库，
 * 上一轮 PUT 的设置会串到下一轮，表现为「单独跑绿、一起跑红」。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AsrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpeechSettingService speechSettingService;

    @AfterEach
    void 清理设置() {
        speechSettingService.delete(SpeechSettingService.KIND_ASR);
    }

    @Test
    void 读设置返回默认值() throws Exception {
        mockMvc.perform(get("/api/speech/asr/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_provider").value("qwen-asr"))
                .andExpect(jsonPath("$.vad_silence_ms").value(800))
                .andExpect(jsonPath("$.send_mode").value("fill_only"))
                .andExpect(jsonPath("$.provider_configs['qwen-asr']").exists());
    }

    @Test
    void 保存后原样读回() throws Exception {
        mockMvc.perform(put("/api/speech/asr/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active_provider\":\"mock-asr\",\"vad_silence_ms\":1200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_provider").value("mock-asr"));

        mockMvc.perform(get("/api/speech/asr/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_provider").value("mock-asr"))
                .andExpect(jsonPath("$.vad_silence_ms").value(1200));
    }

    @Test
    void 保存未知provider返回400且带错误码() throws Exception {
        mockMvc.perform(put("/api/speech/asr/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active_provider\":\"no-such-asr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASR_PROVIDER_NOT_FOUND"));
    }

    @Test
    void 保存非法sendMode返回400() throws Exception {
        mockMvc.perform(put("/api/speech/asr/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active_provider\":\"mock-asr\",\"send_mode\":\"auto\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 保存越界的静音计时返回400() throws Exception {
        // 越界必须带 code + 人话 detail：Spring 默认错误体（timestamp/status/error/path）
        // 没有 code，前端只能显示 "HTTP 400"
        mockMvc.perform(put("/api/speech/asr/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active_provider\":\"mock-asr\",\"vad_silence_ms\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASR_INVALID_SETTINGS"))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        mockMvc.perform(put("/api/speech/asr/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active_provider\":\"mock-asr\",\"vad_silence_ms\":5001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASR_INVALID_SETTINGS"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void 状态接口返回上游契约字段() throws Exception {
        // 前端 asrGetStatus() 只读 vad_loaded / ptt_global_ok；键名不是 snake_case 就拿不到
        mockMvc.perform(get("/api/speech/asr/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vad_loaded").value(false))
                .andExpect(jsonPath("$.ptt_global_ok").value(false))
                .andExpect(jsonPath("$.active_provider").value("qwen-asr"))
                .andExpect(jsonPath("$.session_id").doesNotExist());
    }

    @Test
    void provider列表含三个id() throws Exception {
        mockMvc.perform(get("/api/speech/asr/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("qwen-asr"))
                .andExpect(jsonPath("$[1].id").value("llama-asr"))
                .andExpect(jsonPath("$[2].id").value("mock-asr"))
                // 设置页靠 config_fields 渲染表单，字段名必须是上游的 default_value
                .andExpect(jsonPath("$[0].config_fields[0].default_value").value("cn-beijing"));
    }

    @Test
    void 模型列表默认按当前provider() throws Exception {
        mockMvc.perform(put("/api/speech/asr/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active_provider\":\"mock-asr\"}"))
                .andExpect(status().isOk());

        // mock 没有模型概念：返回空数组也算通过，关键是「不报错」
        mockMvc.perform(get("/api/speech/asr/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 模型列表按显式provider过滤() throws Exception {
        mockMvc.perform(get("/api/speech/asr/models").param("providerId", "qwen-asr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("qwen-audio-3.0-asr-flash"))
                .andExpect(jsonPath("$[0].is_default").value(true))
                .andExpect(jsonPath("$[0].endpoint_kind").value("http"));
    }

    @Test
    void 探活用mock返回成功与耗时() throws Exception {
        mockMvc.perform(post("/api/speech/asr/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"mock-asr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.providerId").value("mock-asr"))
                .andExpect(jsonPath("$.latencyMs").isNumber())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void 探活qwen无key返回400且带错误码() throws Exception {
        mockMvc.perform(post("/api/speech/asr/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"qwen-asr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASR_MISSING_CREDENTIALS"));
    }
}

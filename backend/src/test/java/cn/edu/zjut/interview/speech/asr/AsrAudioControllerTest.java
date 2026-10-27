package cn.edu.zjut.interview.speech.asr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语音音频端点（规格 V2 §3.4）：{@code /vad}、{@code /recognize}、{@code /sessions}。
 *
 * <p>这些用例共享同一个 Spring 上下文与内存库，所以每个用例结束都要收尾：
 * 删会话（否则下一个类第一次 {@code /sessions} 就 409）、把设置恢复成默认
 * （否则 {@code active_provider=mock-asr} 会串到别的类，表现为「单独跑绿、一起跑红」）。
 *
 * <p>VAD 是有状态单例（帧号 + 噪声底），{@code /vad} 的用例开始前借
 * 「开会话再删会话」把它复位——{@code /sessions} 成功时会调 {@code EnergyVad.reset()}。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AsrAudioControllerTest {

    private static final int FRAME = EnergyVad.FRAME_SAMPLES;

    /** 用例间恢复的默认设置（与 {@link AsrSettings#defaults()} 一致的三个关键字段）。 */
    private static final String DEFAULT_SETTINGS =
            "{\"active_provider\":\"qwen-asr\",\"send_mode\":\"fill_only\",\"vad_silence_ms\":800}";

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void 收尾() throws Exception {
        mockMvc.perform(delete("/api/speech/asr/sessions"));
        mockMvc.perform(put("/api/speech/asr/settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(DEFAULT_SETTINGS));
    }

    @Test
    void vad全零PCM返回0事件() throws Exception {
        重置Vad();

        mockMvc.perform(post("/api/speech/asr/vad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pcmJson(new float[FRAME])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isEmpty())
                .andExpect(jsonPath("$.speech_prob").value(0.0))
                .andExpect(jsonPath("$.frame").value(1));
    }

    @Test
    void vad语音PCM返回speech_started() throws Exception {
        重置Vad();

        mockMvc.perform(post("/api/speech/asr/vad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pcmJson(tone(5, 0.3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].type").value("speech_started"))
                // speech_started 没有静音计时，NON_NULL 保证这个键根本不存在
                .andExpect(jsonPath("$.events[0].silence_ms").doesNotExist())
                .andExpect(jsonPath("$.frame").value(5));
    }

    @Test
    void vad一批里可以同时返回多个事件() throws Exception {
        重置Vad();

        // 先 1 帧语音开段，再一次性灌 27 帧静音：同一批里既有 silence_started 也有
        // turn_candidate。这是「批量帧」相对上游「单帧 IPC」的必然差异（差异 B），
        // 事件只能放在数组里，不能像上游那样只回一个 event。
        mockMvc.perform(post("/api/speech/asr/vad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pcmJson(tone(1, 0.3))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/speech/asr/vad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pcmJson(new float[FRAME * 27])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].type").value("silence_started"))
                .andExpect(jsonPath("$.events[0].silence_ms").value(0))
                .andExpect(jsonPath("$.events[1].type").value("turn_candidate"))
                // 27 帧 × 30ms = 810，与上游 silence_frames * 30 逐字一致
                .andExpect(jsonPath("$.events[1].silence_ms").value(810));
    }

    @Test
    void recognize上传合法WAV走mock返回文本() throws Exception {
        mockMvc.perform(put("/api/speech/asr/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active_provider\":\"mock-asr\"}"))
                .andExpect(status().isOk());

        MockMultipartFile file = new MockMultipartFile("wav", "sample.wav", "audio/wav",
                WavCodec.encode(tone(5, 0.3)));

        mockMvc.perform(multipart("/api/speech/asr/recognize").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").isNotEmpty())
                // snake_case 是上游 AsrResult 的契约键；V1 曾序列化成 providerId，V2 修正
                .andExpect(jsonPath("$.provider_id").value("mock-asr"))
                .andExpect(jsonPath("$.providerId").doesNotExist());
    }

    @Test
    void recognize空文件返回400且code为ASR_INVALID_AUDIO() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("wav", "empty.wav", "audio/wav", new byte[0]);

        mockMvc.perform(multipart("/api/speech/asr/recognize").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASR_INVALID_AUDIO"));
    }

    @Test
    void recognize非WAV字节返回400() throws Exception {
        MockMultipartFile notWav = new MockMultipartFile("wav", "x.wav", "audio/wav",
                "definitely not a wav".getBytes(StandardCharsets.US_ASCII));

        mockMvc.perform(multipart("/api/speech/asr/recognize").file(notWav))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASR_INVALID_AUDIO"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void 会话端点建停取消与幂等() throws Exception {
        mockMvc.perform(post("/api/speech/asr/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"button\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").isNotEmpty())
                .andExpect(jsonPath("$.source").value("button"))
                .andExpect(jsonPath("$.started_at").isNotEmpty());

        mockMvc.perform(get("/api/speech/asr/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").isNotEmpty())
                .andExpect(jsonPath("$.active_source").value("button"));

        mockMvc.perform(post("/api/speech/asr/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"auto\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASR_SESSION_BUSY"));

        // stop 幂等：第一次带回结束的那一轮，第二次 200 + 空体
        mockMvc.perform(post("/api/speech/asr/sessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("button"));
        mockMvc.perform(post("/api/speech/asr/sessions/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        // cancel 幂等：无会话时也不报错
        mockMvc.perform(delete("/api/speech/asr/sessions")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/speech/asr/sessions")).andExpect(status().isOk());
    }

    /** 开一次会话再删掉：{@code /sessions} 成功会调 {@code EnergyVad.reset()}。 */
    private void 重置Vad() throws Exception {
        mockMvc.perform(post("/api/speech/asr/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"auto\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/speech/asr/sessions")).andExpect(status().isOk());
    }

    private static float[] tone(int frames, double amplitude) {
        float[] pcm = new float[frames * FRAME];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (float) (amplitude * Math.sin(2 * Math.PI * 440.0 * i / 16_000.0));
        }
        return pcm;
    }

    /** {@code {"pcm":[...]}}。手拼而不是序列化：请求体形状本身就是被测契约。 */
    private static String pcmJson(float[] pcm) {
        StringBuilder body = new StringBuilder(pcm.length * 12 + 16);
        body.append("{\"pcm\":[");
        for (int i = 0; i < pcm.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(pcm[i]);
        }
        return body.append("]}").toString();
    }
}

package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.speech.tts.VoiceFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/speech/audio/{name}} 的三种结果（规格 V6a §6.3）：命中、指纹名但不存在、非法名。
 *
 * <p>与 {@link HealthControllerTest} 同款写法（{@code @SpringBootTest} + {@link MockMvc}）。
 * 文件由容器里的 {@link VoiceFileService} 真写一份：端点读的就是它写的那个目录，
 * 不额外造路径——测的是「合成 → 落盘 → 端点」这条真链路，不是两个假构件碰巧对上。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SpeechAudioControllerTest {

    private static final byte[] AUDIO = {11, 22, 33, 44, 55};

    /** 形状合法（40 位小写十六进制）的指纹名，内容随便取，反正是缓存键。 */
    private static final String NAME = String.format("%040x", 20261028) + ".wav";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VoiceFileService voiceFileService;

    @AfterEach
    void 清掉测试文件() {
        voiceFileService.resolve(NAME).ifPresent(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // 测试收尾，删不掉不影响结论；下次跑同名文件会被覆盖
            }
        });
    }

    @Test
    void 命中缓存返回音频字节() throws Exception {
        voiceFileService.write(NAME, AUDIO);

        mockMvc.perform(get("/api/speech/audio/" + NAME))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"))
                .andExpect(content().bytes(AUDIO))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"));
    }

    @Test
    void 指纹名但文件不存在返回404() throws Exception {
        String missing = String.format("%040x", 404) + ".wav";

        mockMvc.perform(get("/api/speech/audio/" + missing))
                .andExpect(status().isNotFound());
    }

    @Test
    void 非法名返回404() throws Exception {
        // 带 ../ 的名字无论被 Servlet 容器规范化成哪种形态，都过不了文件名白名单 → 404
        mockMvc.perform(get("/api/speech/audio/../secret.wav"))
                .andExpect(status().isNotFound());
    }
}

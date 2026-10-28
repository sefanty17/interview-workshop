package cn.edu.zjut.interview.api;

import cn.edu.zjut.interview.speech.tts.VoiceFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 语音缓存的静态端点（V6a）：{@code GET /api/speech/audio/{name}}。
 *
 * <p><b>实现方式差异</b>：上游把音频写在本地磁盘、把 {@code voice_file} 路径直接塞给前端，
 * 由 Tauri 的 asset 协议去读。浏览器没有这层协议（也读不到任意本地路径），
 * 所以这里统一走一个 HTTP 端点——名字仍然是指纹，对前端来说只是「把这个 URL 交给
 * {@code <audio src>}」，差异被收在本类里，前端播放队列（V6b）不必感知。
 *
 * <p>名字过 {@link VoiceFileService#resolve} 的白名单：非法名 / 不存在一律 404，
 * 不返回目录内容，也不泄露路径。
 */
@RestController
@RequestMapping("/api/speech/audio")
public class SpeechAudioController {

    private static final Logger log = LoggerFactory.getLogger(SpeechAudioController.class);

    /** 内容不可变（名字 = 内容指纹），缓存一天不会读到旧内容。 */
    private static final String CACHE_CONTROL = "public, max-age=86400";

    private final VoiceFileService voiceFileService;

    public SpeechAudioController(VoiceFileService voiceFileService) {
        this.voiceFileService = voiceFileService;
    }

    /**
     * 取一份已合成的音频。
     *
     * @return 200 + 音频字节；名字非法或文件不存在 → 404（无 body）
     */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> audio(@PathVariable String name) {
        Optional<Path> file = voiceFileService.resolve(name);
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.get());
        } catch (IOException e) {
            // 列举到、却读不出来（被外部删了 / 权限变了）：按「没这个文件」处理，不暴露磁盘细节
            log.warn("[SpeechAudio] 读取语音缓存失败：{}", e.toString());
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(voiceFileService.mediaTypeFor(name)))
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(bytes);
    }
}

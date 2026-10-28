package cn.edu.zjut.interview.speech.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;

/**
 * 对话链路用的语音合成入口（V6a）：把 {@link TtsService} 的合成结果落盘，返回可播放的 URL。
 *
 * <p>与 {@link TtsService} 的分工：那边是「设置页 / 试听」的同步调用，失败要冒泡成
 * {@code {code, detail}} 让用户看见；这边是「每句台词」的旁路调用，<b>失败一律静默</b>——
 * TTS 出问题的表现只能是「这一句没声音」，绝不能是「对话断了」。所以对外只认
 * {@link Optional}：empty = 本句不出声，调用方无需 try/catch。
 *
 * <p>缓存文件名是内容指纹，内容不可变 ⇒ 命中即复用、HTTP 层可长期缓存
 * （见 {@link VoiceFileService} 的类注释）。
 */
@Service
public class TtsVoiceService {

    private static final Logger log = LoggerFactory.getLogger(TtsVoiceService.class);

    private final TtsSettingsService settingsService;
    private final TtsService ttsService;
    private final VoiceFileService voiceFileService;

    public TtsVoiceService(TtsSettingsService settingsService, TtsService ttsService,
                           VoiceFileService voiceFileService) {
        this.settingsService = settingsService;
        this.ttsService = ttsService;
        this.voiceFileService = voiceFileService;
    }

    /**
     * 合成本句台词，成功返回可直接给 {@code <audio src>} 的 URL，失败返回 empty。
     *
     * @param text 要念的正文（已剥标签）
     * @param emo  模型原样输出的情绪词，{@code null} 按空串处理
     */
    public Optional<String> synthesize(String text, String emo) {
        try {
            TtsSettings settings = settingsService.load();

            // 1) 设置页的总开关：关了就一次都不许请求（用户明确关掉的，不能偷偷烧钱 / 拖慢对话）
            if (!settings.isTtsOn()) {
                return Optional.empty();
            }
            // 2) 空文本：没有可念的内容，直接跳过（纯标签句到这里已经是空串）
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            // 3) 引擎：edge-tts / local-sbv2 是本项目确定不做的，静默跳过而不是报错——
            //    理由同上：默认设置就是 edge-tts，报错等于「一开 TTS 就炸」
            String type = settings.getTtsType();
            if (!TtsService.SUPPORTED_TYPES.contains(type)) {
                return Optional.empty();
            }
            // 4) 音频格式：空 / 不在白名单里就回落 wav（设置项是被前端表单自由填的，必须兜）
            String ext = settings.getTtsAudioFormat();
            if (!VoiceFileService.isAllowedExtension(ext)) {
                ext = "wav";
            } else {
                ext = ext.toLowerCase(Locale.ROOT);
            }

            String safeEmo = emo == null ? "" : emo;
            String name = sha1Hex(type + "\0" + safeEmo + "\0" + ext + "\0" + text) + "." + ext;

            // 5) 指纹命中：同一个引擎 + 同一句 + 同一情绪，直接复用，不重复烧钱
            if (voiceFileService.resolve(name).isPresent()) {
                return Optional.of(voiceFileService.urlFor(name));
            }

            byte[] audio = ttsService.synthesizeAs(type, text, safeEmo);
            if (audio == null || audio.length == 0) {
                log.warn("[TTS] 合成返回空音频，本句不出声：type={}", type);
                return Optional.empty();
            }
            voiceFileService.write(name, audio);
            return Optional.of(voiceFileService.urlFor(name));
        } catch (RuntimeException e) {
            // TtsException 也是 RuntimeException：这里兜住一切，只留一条 warn
            log.warn("[TTS] 合成失败，本句不出声：{}", e.toString());
            return Optional.empty();
        }
    }

    /** SHA-1 的小写十六进制（40 位），UTF-8 编码。指纹只用于命中缓存，不用于防碰撞场景。 */
    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 是 JDK 强制实现的算法，走到这里只能是环境问题；抛出去由调用方静默掉
            throw new IllegalStateException("JDK 不支持 SHA-1", e);
        }
    }
}

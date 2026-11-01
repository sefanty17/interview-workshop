package cn.edu.zjut.interview.speech.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ASR 录音落盘（issue #8 的验收项）：把每次识别上传的 WAV 存到
 * {@code backend/data/audio/<场次ID>/<轮次>.wav}，并暴露可播放的 GET 地址。
 *
 * <p>为什么存 WAV 而不是 WebM：本项目的录音链路是「f32 PCM → 16k mono PCM16 WAV」，
 * 识别上传的已经是 WAV；再转一次 WebM 只会多一次编码与一个依赖，播放效果不变。
 * 目录名与识别请求里的场次/轮次对齐，方便后续表达分析（#9）按轮取音频。
 *
 * <p>路径安全用白名单而不是 normalize 兜底：场次只允许数字（或项目自定义的
 * {@code adhoc}），文件名只允许数字 + {@code .wav}。形状不对一律按「不存在」，
 * 因此 {@code ../}、绝对路径、额外后缀都进不了文件系统。
 */
@Service
public class AsrRecordingStore {

    private static final Logger log = LoggerFactory.getLogger(AsrRecordingStore.class);

    /** 场次目录：后端场次是数字 ID；没有场次上下文时用固定目录。 */
    private static final Pattern SESSION = Pattern.compile("[0-9]{1,18}");

    /** 文件名：轮次（可带时间戳去重）+ .wav。 */
    private static final Pattern NAME = Pattern.compile("[0-9]{1,18}(-[0-9]{1,18})?\\.wav");

    private static final String ADHOC = "adhoc";

    private final Path dir;

    /** Spring 用的构造器：默认 {@code data/audio}（相对 backend 工作目录）。 */
    @Autowired
    public AsrRecordingStore(@Value("${app.asr.recordings-dir:data/audio}") String dir) {
        this(Path.of(dir));
    }

    /** 测试 / 自定义目录用的构造器。 */
    public AsrRecordingStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("[ASR] 录音目录创建失败：" + dir, e);
        }
        log.info("[ASR] 录音目录 = {}", dir.toAbsolutePath());
    }

    public Path dir() {
        return dir;
    }

    /**
     * 保存一次识别上传的 WAV。
     *
     * @param wav       上传的原始 WAV 字节（调用方已校验过格式）
     * @param sessionId 场次 ID；null / 非正数落到 {@code adhoc}
     * @param turn      轮次；null / 非正数用当前毫秒时间戳
     * @return 可直接播放的 URL（GET）
     */
    public String save(byte[] wav, Long sessionId, Integer turn) {
        if (wav == null || wav.length == 0) {
            throw new IllegalArgumentException("录音内容为空");
        }
        String session = sessionId != null && sessionId > 0 ? sessionId.toString() : ADHOC;
        String base = turn != null && turn > 0 ? turn.toString() : String.valueOf(System.currentTimeMillis());
        String name = base + ".wav";

        Path sessionDir = dir.resolve(session);
        try {
            Files.createDirectories(sessionDir);
            Path target = sessionDir.resolve(name);
            // 同一轮重试（云端识别失败后重录）不能覆盖上一份录音：加时间戳区分
            if (Files.exists(target)) {
                name = base + "-" + Instant.now().toEpochMilli() + ".wav";
                target = sessionDir.resolve(name);
            }
            Files.write(target, wav);
            return urlFor(session, name);
        } catch (IOException e) {
            throw new UncheckedIOException("[ASR] 录音写入失败：" + session + "/" + name, e);
        }
    }

    /**
     * 按 URL 参数定位录音。
     *
     * @return 参数不合法或文件不存在 → {@link Optional#empty()}
     */
    public Optional<Path> resolve(String session, String name) {
        if (!isLegalSession(session) || !isLegalName(name)) {
            return Optional.empty();
        }
        Path file = dir.resolve(session).resolve(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** 对外可访问的 URL 前缀，与 {@link cn.edu.zjut.interview.api.AsrController} 的映射一致。 */
    public String urlFor(String session, String name) {
        return "/api/speech/asr/recordings/" + session + "/" + name;
    }

    /** 录音只有 WAV 一种格式；不按扩展名猜 MIME。 */
    public static String contentType() {
        return "audio/wav";
    }

    private static boolean isLegalSession(String session) {
        return session != null && (ADHOC.equals(session) || SESSION.matcher(session).matches());
    }

    private static boolean isLegalName(String name) {
        return name != null && NAME.matcher(name).matches();
    }
}

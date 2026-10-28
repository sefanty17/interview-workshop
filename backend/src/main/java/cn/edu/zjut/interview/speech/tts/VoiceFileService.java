package cn.edu.zjut.interview.speech.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 语音缓存文件的读写与回收：对应上游把音频写进 {@code voice_file} 路径的那一半。
 *
 * <p>文件名<b>就是内容指纹</b>（{@code sha1(ttsType \0 emo \0 ext \0 text).ext}，见
 * {@link TtsVoiceService}）：内容不可变，所以命中即复用，HTTP 层也能长期缓存。
 * 名字不是由外部传入的自由串，因此这里用<b>白名单</b>而不是「拼路径再 normalize 兜底」：
 * 只要名字的形状不对（含 {@code /}、{@code ..}、额外后缀），一律按「不存在」处理，
 * 路径穿越在进 {@code resolve} 之前就被掐掉。
 *
 * <p>目录与 {@code data/interview.mv.db} 同级（相对当前工作目录），后端与单测都在
 * {@code backend/} 下启动，所以两边看到的是同一个 {@code data/tts-cache}。
 */
@Service
public class VoiceFileService {

    private static final Logger log = LoggerFactory.getLogger(VoiceFileService.class);

    /**
     * 合法文件名：40 位小写十六进制 + 点 + 2~4 位小写扩展名。
     *
     * <p>形状收紧到这个程度之后，{@link Path#resolve(String)} 的结果不可能逃出本目录，
     * 也不需要再判 {@code normalize().startsWith(dir)}。
     */
    private static final Pattern NAME = Pattern.compile("[a-f0-9]{40}\\.[a-z0-9]{2,4}");

    /** 允许落盘的音频扩展名；不在表里的一律视为非法名（连 {@code .exe} 之类的拼接都挡掉）。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("wav", "mp3", "ogg", "flac", "aac", "opus");

    /** 缓存文件上限：一局面试的台词量级在百条以内，300 条足够，超了按最旧先删。 */
    public static final int DEFAULT_MAX_FILES = 300;

    private final Path dir;
    private final int maxFiles;

    /** Spring 用的构造器：默认目录 + 默认上限。 */
    public VoiceFileService() throws IOException {
        this(Path.of("data/tts-cache"), DEFAULT_MAX_FILES);
    }

    /** 测试 / 自定义目录用的构造器。 */
    public VoiceFileService(Path dir, int maxFiles) throws IOException {
        this.dir = dir;
        this.maxFiles = maxFiles;
        Files.createDirectories(dir);
    }

    /** 当前缓存目录（测试用来确认落盘位置、控制器不直接依赖）。 */
    public Path dir() {
        return dir;
    }

    /**
     * 按名字定位已缓存的文件。
     *
     * @return 名字非法或文件不存在 → {@link Optional#empty()}
     */
    public Optional<Path> resolve(String name) {
        if (!isLegalName(name)) {
            return Optional.empty();
        }
        Path file = dir.resolve(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /**
     * 写一份音频并按需回收。
     *
     * @return 传入的文件名（调用方拿它拼 URL）
     * @throws IllegalArgumentException 名字非法
     */
    public String write(String name, byte[] audio) {
        if (!isLegalName(name)) {
            throw new IllegalArgumentException("非法语音文件名：" + name);
        }
        try {
            Files.write(dir.resolve(name), audio);
        } catch (IOException e) {
            throw new UncheckedIOException("[TTS] 语音缓存写入失败：" + name, e);
        }
        evictIfNeeded();
        return name;
    }

    /** 对外可访问的 URL 前缀，与 {@link cn.edu.zjut.interview.api.SpeechAudioController} 的映射一致。 */
    public String urlFor(String name) {
        return "/api/speech/audio/" + name;
    }

    /**
     * 文件数超过上限就删最旧的，删到恰好 {@code maxFiles} 个。
     *
     * <p>按 {@code lastModified} 升序删 = LRU：最近用过的句子（用户回听、重试同一句）
     * 留在盘上，只有长期没人碰的老句子会让位。删失败只 warn 不抛——清理是锦上添花，
     * 清理不干净不该让「本次合成」失败。
     */
    public void evictIfNeeded() {
        List<Path> all;
        try (Stream<Path> files = Files.list(dir)) {
            all = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(VoiceFileService::lastModifiedOf))
                    .toList();
        } catch (IOException e) {
            log.warn("[TTS] 语音缓存目录列举失败，跳过本次回收：{}", e.toString());
            return;
        }
        for (int i = 0; i < all.size() - maxFiles; i++) {
            try {
                Files.deleteIfExists(all.get(i));
            } catch (IOException e) {
                log.warn("[TTS] 删除旧语音缓存失败：{} {}", all.get(i), e.toString());
            }
        }
    }

    /** 响应体的 Content-Type。不认识的扩展名给二进制流，浏览器会下载而不是播错。 */
    public String mediaTypeFor(String name) {
        return switch (extensionOf(name)) {
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "aac" -> "audio/aac";
            case "opus" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }

    /** 扩展名是否在白名单里（{@link TtsVoiceService} 校验设置里的音频格式时也用它）。 */
    public static boolean isAllowedExtension(String ext) {
        return ext != null && ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }

    /** 名字合法性 = 形状匹配 + 扩展名在白名单内。 */
    private static boolean isLegalName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return false;
        }
        return isAllowedExtension(extensionOf(name));
    }

    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static long lastModifiedOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}

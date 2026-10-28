package cn.edu.zjut.interview.speech.tts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoiceFileService} 的白名单与回收（规格 V6a §6.2）。
 *
 * <p>重点是「非法名一律当不存在」：{@code ../secret.wav} 这种名字在进
 * {@code resolve} 之前就被形状挡掉，而不是拼好路径再判断有没有跑出去。
 */
class VoiceFileServiceTest {

    private static final byte[] AUDIO = {7, 6, 5, 4};

    @TempDir
    private Path dir;

    private VoiceFileService service;

    @BeforeEach
    void 建服务() throws IOException {
        service = new VoiceFileService(dir, 5);
    }

    @Test
    void 写入后可解析回来且字节一致() throws IOException {
        String name = fingerprint(1) + ".wav";

        assertEquals(name, service.write(name, AUDIO), "write 回传文件名，调用方拿它拼 URL");

        Optional<Path> file = service.resolve(name);
        assertTrue(file.isPresent(), "写完必须能解析回来");
        assertArrayEquals(AUDIO, Files.readAllBytes(file.get()));
        assertEquals("/api/speech/audio/" + name, service.urlFor(name));
        assertEquals("audio/wav", service.mediaTypeFor(name));
    }

    @Test
    void 路径穿越的名字被拒() {
        for (String bad : List.of("../secret.wav", "a/b.wav", "..\\secret.wav")) {
            assertEquals(Optional.empty(), service.resolve(bad), "穿越名按不存在处理：" + bad);
            assertThrows(IllegalArgumentException.class, () -> service.write(bad, AUDIO),
                    "穿越名写盘必须直接被拒：" + bad);
        }
    }

    @Test
    void 非指纹名被拒() {
        // 末位是大写：指纹一律小写，大写名说明不是本服务写出来的
        String uppercase = "ABCDEF0123456789ABCDEF0123456789ABCDEF01" + ".wav";
        for (String bad : List.of("abc.wav", "", "a.wav.exe", uppercase)) {
            assertEquals(Optional.empty(), service.resolve(bad), "非指纹名按不存在处理：" + bad);
            assertThrows(IllegalArgumentException.class, () -> service.write(bad, AUDIO),
                    "非指纹名写盘必须直接被拒：" + bad);
        }
    }

    @Test
    void LRU超限删最旧() throws IOException {
        // 逐个写并显式指定 lastModified：Windows 上短时间内连续写可能拿到同一个时间戳，
        // 排序就退化成「不确定」，这里把顺序钉死，断言才有意义
        long base = System.currentTimeMillis() - 1_000_000L;
        for (int i = 0; i < 8; i++) {
            String name = fingerprint(i) + ".wav";
            service.write(name, AUDIO);
            Files.setLastModifiedTime(dir.resolve(name), FileTime.fromMillis(base + i * 1_000L));
        }

        Set<String> left = names();
        assertEquals(5, left.size(), "超过 maxFiles=5 后必须删到只剩 5 个");
        assertEquals(Set.of(fingerprint(3) + ".wav", fingerprint(4) + ".wav", fingerprint(5) + ".wav",
                fingerprint(6) + ".wav", fingerprint(7) + ".wav"), left, "留下的是后写的 5 个");
    }

    @Test
    void 扩展名白名单() {
        assertTrue(service.resolve(fingerprint(9) + ".pdf").isEmpty(), "pdf 不在音频扩展名白名单里");
        assertThrows(IllegalArgumentException.class, () -> service.write(fingerprint(9) + ".pdf", AUDIO));

        String wav = fingerprint(9) + ".wav";
        service.write(wav, AUDIO);
        assertTrue(service.resolve(wav).isPresent(), "wav 合法，应能落盘");
        assertEquals("audio/wav", service.mediaTypeFor(wav));
        assertEquals("audio/mpeg", service.mediaTypeFor(fingerprint(9) + ".mp3"), "mp3 → audio/mpeg");
        assertEquals("application/octet-stream", service.mediaTypeFor(fingerprint(9) + ".bin"),
                "不在表里的扩展名给二进制流");
    }

    // ---------------- 工具 ----------------

    /** 40 位小写十六进制，形状与真实指纹一致（内容是什么无所谓，形状对了才测得到白名单）。 */
    private static String fingerprint(int seed) {
        return String.format("%040x", seed);
    }

    private Set<String> names() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }
}

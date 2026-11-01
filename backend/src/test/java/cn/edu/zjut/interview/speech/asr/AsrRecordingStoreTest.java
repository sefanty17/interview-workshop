package cn.edu.zjut.interview.speech.asr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #8 的录音落盘验收：按场次/轮次命名、可回放、路径不越界。
 *
 * <p>不启 Spring：这里只测文件系统行为，provider / 数据库都不参与。
 */
class AsrRecordingStoreTest {

    private static final byte[] WAV = new byte[]{0x52, 0x49, 0x46, 0x46, 0x00, 0x01};

    @TempDir
    Path tempDir;

    @Test
    void 按场次与轮次落盘并可回放() throws Exception {
        AsrRecordingStore store = new AsrRecordingStore(tempDir);

        String url = store.save(WAV, 7L, 2);

        assertEquals("/api/speech/asr/recordings/7/2.wav", url);
        Path file = tempDir.resolve("7").resolve("2.wav");
        assertTrue(Files.isRegularFile(file), "录音没有落盘：" + file);
        assertArrayEquals(WAV, Files.readAllBytes(file));
        assertTrue(store.resolve("7", "2.wav").isPresent(), "落盘后应能按 URL 参数找到");
    }

    @Test
    void 没有场次上下文时落到adhoc并用时间戳命名() throws Exception {
        AsrRecordingStore store = new AsrRecordingStore(tempDir);

        String url = store.save(WAV, null, null);

        assertTrue(url.startsWith("/api/speech/asr/recordings/adhoc/"), url);
        assertTrue(url.endsWith(".wav"));
        assertTrue(Files.list(tempDir.resolve("adhoc")).findAny().isPresent(), "adhoc 目录应已建立");
    }

    @Test
    void 非法路径参数一律拒绝() throws Exception {
        AsrRecordingStore store = new AsrRecordingStore(tempDir);
        store.save(WAV, 1L, 1);

        assertFalse(store.resolve("..", "1.wav").isPresent(), "场次不允许路径穿越");
        assertFalse(store.resolve("1", "../1.wav").isPresent(), "文件名不允许路径穿越");
        assertFalse(store.resolve("1", "1.exe").isPresent(), "只允许 .wav");
        assertFalse(store.resolve("404", "1.wav").isPresent(), "不存在的场次返回空");
    }

    @Test
    void 同轮重试不会覆盖上一份录音() throws Exception {
        AsrRecordingStore store = new AsrRecordingStore(tempDir);

        String first = store.save(WAV, 1L, 1);
        String second = store.save(WAV, 1L, 1);

        assertTrue(first.endsWith("/1.wav"), first);
        assertTrue(second.contains("/1-") && second.endsWith(".wav"), second);
        assertEquals(2, Files.list(tempDir.resolve("1")).count(), "两份录音都应保留");
    }
}
